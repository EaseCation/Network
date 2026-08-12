package com.nukkitx.network.raknet;

import com.nukkitx.network.SessionConnection;
import com.nukkitx.network.raknet.util.*;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.network.util.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnegative;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nukkitx.network.raknet.RakNetConstants.*;

@ParametersAreNonnullByDefault
public abstract class RakNetSession implements SessionConnection<ByteBuf> {
    protected static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetSession.class);

    private static final long SEQUENCE_INDEX_MASK = 0xFFFFFFL;
    private static final long SEQUENCE_INDEX_COUNT = SEQUENCE_INDEX_MASK + 1;
    private static final long MAXIMUM_ACKNOWLEDGE_DISTANCE = SEQUENCE_INDEX_COUNT >>> 1;
    private static final int MAXIMUM_RELIABILITY_GAP = 8192; // 1000000 in vanilla RakNet
    private static final int MAXIMUM_ORDERING_GAP = 8192;
    private static final int MAXIMUM_ORDERED_PACKET_COUNT = 8192;
    private static final int MAXIMUM_PENDING_SPLIT_HELPERS = 256;
    private static final int MAXIMUM_PENDING_SPLIT_PARTS = MAXIMUM_SPLIT_PACKET_COUNT;
    private static final long MAXIMUM_PENDING_BYTES = 64L * 1024 * 1024;

    final InetSocketAddress address;
    InetSocketAddress proxiedAddress = null;
    final Channel channel;
    final EventLoop eventLoop;
    final int protocolVersion;
    private int mtu;
    private int adjustedMtu; // Used in datagram calculations
    long guid;
    private volatile RakNetState state = RakNetState.UNCONNECTED;
    private final long createdAt = System.currentTimeMillis();
    private volatile long lastTouched = System.currentTimeMillis();
    private volatile boolean closed = false;
    private final AtomicBoolean closing = new AtomicBoolean();

    // Reliability, Ordering, Sequencing and datagram indexes
    private RakNetSlidingWindow slidingWindow;
    private int splitIndex;
    private int reliabilityReadIndex;
    private int reliabilityWriteIndex;
    private int[] orderReadIndex;
    private long[] orderReadCount;
    private int[] orderWriteIndex;
    // private int[] sequenceReadIndex;
    // private int[] sequenceWriteIndex;

    private Int2ObjectMap<SplitPacketHelper> splitPackets;
    private BitQueue reliableDatagramQueue;

    private FastBinaryMinHeap outgoingPackets;
    private long[] outgoingPacketNextWeights;
    private FastBinaryMinHeap[] orderingHeaps;
    private int pendingOrderedPackets;
    private long pendingOrderedBytes;
    private int pendingSplitParts;
    private long pendingSplitBytes;
    @Getter
    @Setter
    private volatile RakNetSessionListener listener = null;
    private volatile long currentPingTime = -1;
    private volatile long lastPingTime = -1;
    private volatile long lastPongTime = -1;
    private ConcurrentNavigableMap<Integer, RakNetDatagram> sentDatagrams;
    private Queue<AcknowledgeRange> incomingAcks;
    private Queue<AcknowledgeRange> incomingNaks;
    private int pendingAcknowledgeRanges;
    private Queue<IntRange> outgoingAcks;
    private Queue<IntRange> outgoingNaks;
    private long datagramWriteCount;
    private int unackedBytes;
    private long queuedBytes;
    private long lastMinWeight;
    private int sessionTimeout = SESSION_TIMEOUT_MS;
    private boolean bandwidthExceededStatistic;

    RakNetSession(InetSocketAddress address, Channel channel, EventLoop eventLoop, int mtu, int protocolVersion) {
        this.address = address;
        this.channel = channel;
        this.eventLoop = eventLoop;
        this.setMtu(mtu);
        this.protocolVersion = protocolVersion;
    }

    final void initialize() {
        Preconditions.checkState(this.state == RakNetState.INITIALIZING);

        this.slidingWindow = new RakNetSlidingWindow(this.mtu, this.adjustedMtu);
        this.bandwidthExceededStatistic = false;

        this.reliableDatagramQueue = new BitQueue(512);
        this.orderReadIndex = new int[MAXIMUM_ORDERING_CHANNELS];
        this.orderReadCount = new long[MAXIMUM_ORDERING_CHANNELS];
        this.orderWriteIndex = new int[MAXIMUM_ORDERING_CHANNELS];
        // this.sequenceReadIndex = new int[MAXIMUM_ORDERING_CHANNELS];
        // this.sequenceWriteIndex = new int[MAXIMUM_ORDERING_CHANNELS];

        this.orderingHeaps = new FastBinaryMinHeap[MAXIMUM_ORDERING_CHANNELS];
        this.splitPackets = new Int2ObjectOpenHashMap<>(256);
        this.sentDatagrams = new ConcurrentSkipListMap<>();
        this.datagramWriteCount = 0;
        this.pendingOrderedPackets = 0;
        this.pendingOrderedBytes = 0;
        this.pendingSplitParts = 0;
        this.pendingSplitBytes = 0;
        this.queuedBytes = 0;
        for (int i = 0; i < MAXIMUM_ORDERING_CHANNELS; i++) {
            orderingHeaps[i] = new FastBinaryMinHeap(64);
        }

        this.outgoingPackets = new FastBinaryMinHeap(8);

        this.incomingAcks = PlatformDependent.newMpscQueue();
        this.incomingNaks = PlatformDependent.newMpscQueue();
        this.pendingAcknowledgeRanges = 0;
        this.outgoingAcks = PlatformDependent.newMpscQueue();
        this.outgoingNaks = PlatformDependent.newMpscQueue();

        this.outgoingPacketNextWeights = new long[4];
        this.initHeapWeights();
    }

    private void deinitialize() {
        Int2ObjectMap<SplitPacketHelper> splitPackets = this.splitPackets;
        this.splitPackets = null;
        if (splitPackets != null) {
            splitPackets.values().forEach(ReferenceCountUtil::safeRelease);
            splitPackets.clear();
        }

        ConcurrentNavigableMap<Integer, RakNetDatagram> sentDatagrams = this.sentDatagrams;
        this.sentDatagrams = null;
        if (sentDatagrams != null) {
            sentDatagrams.values().forEach(ReferenceCountUtil::safeRelease);
            sentDatagrams.clear();
        }

        FastBinaryMinHeap[] orderingHeaps = this.orderingHeaps;
        this.orderingHeaps = null;
        if (orderingHeaps != null) {
            for (FastBinaryMinHeap orderingHeap : orderingHeaps) {
                EncapsulatedPacket packet;
                while ((packet = orderingHeap.poll()) != null) {
                    ReferenceCountUtil.safeRelease(packet);
                }
            }
        }

        FastBinaryMinHeap outgoingPackets = this.outgoingPackets;
        this.outgoingPackets = null;
        if (outgoingPackets != null) {
            EncapsulatedPacket packet;
            while ((packet = outgoingPackets.poll()) != null) {
                ReferenceCountUtil.safeRelease(packet);
            }
        }
        this.reliableDatagramQueue = null;
        clearQueue(this.incomingAcks);
        this.incomingAcks = null;
        clearQueue(this.incomingNaks);
        this.incomingNaks = null;
        this.pendingAcknowledgeRanges = 0;
        clearQueue(this.outgoingAcks);
        this.outgoingAcks = null;
        clearQueue(this.outgoingNaks);
        this.outgoingNaks = null;
        this.pendingOrderedPackets = 0;
        this.pendingOrderedBytes = 0;
        this.pendingSplitParts = 0;
        this.pendingSplitBytes = 0;
        this.queuedBytes = 0;
    }

    private static void clearQueue(@Nullable Queue<?> queue) {
        if (queue != null) {
            queue.clear();
        }
    }

    public InetSocketAddress getAddress() {
        return this.address;
    }

    @Override
    public InetSocketAddress getRealAddress() {
        InetSocketAddress proxied = this.proxiedAddress;
        return proxied == null ? this.address : proxied;
    }

    public int getMtu() {
        return this.mtu;
    }

    void setMtu(int mtu) {
        this.mtu = RakNetUtils.clamp(mtu, MINIMUM_MTU_SIZE, MAXIMUM_MTU_SIZE);
        this.adjustedMtu = (this.mtu - UDP_HEADER_SIZE) - (this.address.getAddress() instanceof Inet6Address ? IPV6_HEADER_SIZE : IPV4_HEADER_SIZE);
        this.slidingWindow = new RakNetSlidingWindow(this.mtu, this.adjustedMtu);
    }

    public int getProtocolVersion() {
        return this.protocolVersion;
    }

    public long getPing() {
        return this.lastPongTime - this.lastPingTime;
    }

    public double getRTT() {
        return this.slidingWindow.getRTT();
    }

    public ByteBuf allocateBuffer(int capacity) {
        return this.channel.alloc().ioBuffer(capacity);
    }

    private void initHeapWeights() {
        for (int priorityLevel = 0; priorityLevel < 4; priorityLevel++) {
            this.outgoingPacketNextWeights[priorityLevel] = (1L << priorityLevel) * priorityLevel + priorityLevel;
        }
    }

    private long getNextWeight(RakNetPriority priority) {
        int priorityLevel = priority.ordinal();
        long next = this.outgoingPacketNextWeights[priorityLevel];

        if (!this.outgoingPackets.isEmpty()) {
            if (next >= this.lastMinWeight) {
                next = this.lastMinWeight + (1L << priorityLevel) * priorityLevel + priorityLevel;
                this.outgoingPacketNextWeights[priorityLevel] = next + (1L << priorityLevel) * (priorityLevel + 1) + priorityLevel;
            }
        } else {
            this.initHeapWeights();
        }
        this.lastMinWeight = next - (1L << priorityLevel) * priorityLevel + priorityLevel;
        return next;
    }

    private EncapsulatedPacket getReassembledPacket(EncapsulatedPacket splitPacket) {
        this.checkForClosed();

        int partId = splitPacket.getPartId();
        SplitPacketHelper helper = this.splitPackets.get(partId);
        if (helper != null && helper.expired()) {
            this.removeSplitPacketHelper(partId, helper);
            helper = null;
        }
        if (helper == null) {
            if (this.splitPackets.size() >= MAXIMUM_PENDING_SPLIT_HELPERS || this.pendingSplitParts + splitPacket.getPartCount() > MAXIMUM_PENDING_SPLIT_PARTS) {
                this.removeExpiredSplitPacketHelpers();
            }
            if (this.splitPackets.size() >= MAXIMUM_PENDING_SPLIT_HELPERS || this.pendingSplitParts + splitPacket.getPartCount() > MAXIMUM_PENDING_SPLIT_PARTS) {
                log.debug("Split packet queue too long for {}", this.address);
                this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
                return null;
            }
            helper = new SplitPacketHelper(partId, splitPacket.getPartCount());
            this.splitPackets.put(partId, helper);
            this.pendingSplitParts += splitPacket.getPartCount();
        } else if (!helper.isCompatible(splitPacket)) {
            log.debug("Received inconsistent split packet metadata from {}", this.address);
            this.disconnect(DisconnectReason.BAD_PACKET);
            return null;
        }

        int previousPartCount = helper.getReceivedPartCount();
        long previousSize = helper.getTotalSize();
        EncapsulatedPacket result;
        try {
            result = helper.add(splitPacket, this);
        } catch (RuntimeException | Error throwable) {
            this.pendingSplitBytes += helper.getTotalSize() - previousSize;
            this.removeSplitPacketHelper(partId, helper);
            throw throwable;
        }
        this.pendingSplitBytes += helper.getTotalSize() - previousSize;
        if (helper.getReceivedPartCount() != previousPartCount && this.pendingSplitBytes > MAXIMUM_PENDING_BYTES) {
            this.removeSplitPacketHelper(partId, helper);
            ReferenceCountUtil.safeRelease(result);
            log.debug("Split packet byte queue too long for {}", this.address);
            this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
            return null;
        }
        if (result != null) {
            this.removeSplitPacketHelper(partId, helper);
        }

        return result;
    }

    private void removeExpiredSplitPacketHelpers() {
        Iterator<SplitPacketHelper> iterator = this.splitPackets.values().iterator();
        while (iterator.hasNext()) {
            SplitPacketHelper helper = iterator.next();
            if (helper.expired()) {
                iterator.remove();
                this.releaseSplitPacketHelper(helper);
            }
        }
    }

    private void removeSplitPacketHelper(int partId, SplitPacketHelper helper) {
        if (this.splitPackets.get(partId) == helper) {
            this.splitPackets.remove(partId);
            this.releaseSplitPacketHelper(helper);
        }
    }

    private void releaseSplitPacketHelper(SplitPacketHelper helper) {
        this.pendingSplitParts -= helper.getPartCount();
        this.pendingSplitBytes -= helper.getTotalSize();
        helper.release();
    }

    public void onDatagram(ByteBuf buffer) {
        try {
            if (this.isClosingOrClosed()) {
                return;
            }
            if (!buffer.isReadable()) {
                return;
            }
            if (buffer.readableBytes() > this.adjustedMtu) {
                if (log.isDebugEnabled()) {
                    log.debug("Received oversized datagram from {} (size: {}, MTU: {})", this.address, buffer.readableBytes(), this.adjustedMtu);
                }
                this.disconnect(DisconnectReason.BAD_PACKET);
                return;
            }

            byte potentialFlags = buffer.getByte(buffer.readerIndex());
            boolean rakNetDatagram = (potentialFlags & FLAG_VALID) != 0;
            if (!rakNetDatagram) {
                // Received non-datagram packet
                this.onPacketInternal(buffer);
                return;
            }

            if (this.state == null || this.state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
                // Block RakNet datagrams if we haven't initialized the session yet.
                return;
            }

            // Check if we have received acknowledge datagram
            if ((potentialFlags & FLAG_ACK) != 0) {
                buffer.readByte();
                if (this.onAcknowledge(buffer, this.incomingAcks, false)) {
                    this.touch();
                }
            } else if ((potentialFlags & FLAG_NACK) != 0) {
                buffer.readByte();
                if (this.onAcknowledge(buffer, this.incomingNaks, true)) {
                    this.touch();
                }
            } else {
                this.onRakNetDatagram(buffer);
            }
        } finally {
            buffer.release();
        }
    }

    private void onEncapsulatedInternal(EncapsulatedPacket packet) {
        ByteBuf buffer = packet.buffer;
        short packetId = buffer.readUnsignedByte();
        switch (packetId) {
            case ID_CONNECTED_PING:
                this.onConnectedPing(buffer);
                break;
            case ID_CONNECTED_PONG:
                this.onConnectedPong(buffer);
                break;
            case ID_DISCONNECTION_NOTIFICATION:
                this.onDisconnectionNotification();
                break;
            default:
                buffer.readerIndex(0);
                if (packetId >= ID_USER_PACKET_ENUM) {
                    // Forward to user
                    if (this.listener != null) {
                        this.listener.onEncapsulated(packet);
                    }
                } else {
                    this.onPacket(buffer);
                }
                break;
        }
    }

    private void onPacketInternal(ByteBuf buffer) {
        short packetId = buffer.getUnsignedByte(buffer.readerIndex());
        if (packetId >= ID_USER_PACKET_ENUM) {
            // Forward to user
            this.touch();
            if (this.listener != null) {
                this.listener.onDirect(buffer);
            }
        } else {
            this.onPacket(buffer);
        }
    }

    protected abstract void onPacket(ByteBuf buffer);

    private void onRakNetDatagram(ByteBuf buffer) {
        if (this.state == null || RakNetState.INITIALIZED.compareTo(this.state) > 0) {
            return;
        }

        RakNetDatagram datagram = new RakNetDatagram(System.currentTimeMillis());
        datagram.decode(buffer);
        if (!datagram.isValid() || datagram.packets.isEmpty()) {
            return;
        }

        for (EncapsulatedPacket encapsulated : datagram.packets) {
            if (!this.isEncapsulatedPacketValid(encapsulated)) {
                this.disconnect(DisconnectReason.BAD_PACKET);
                return;
            }
        }
        this.touch();

        int missedDatagrams = this.slidingWindow.onPacketReceived(datagram.sendTime, datagram.sequenceIndex);
        if (missedDatagrams == -1) {
            log.debug("Too many missed datagrams. [{}] disconnected", this.address);
            this.disconnect(DisconnectReason.TIMED_OUT);
            return;
        }

        this.offerMissingDatagrams(datagram.sequenceIndex, missedDatagrams);

        RakMetrics metrics = this.getRakNet().getMetrics();
        if (metrics != null) {
            metrics.rakDatagramsIn(1);
        }

        boolean ackImmediate = false;
        for (final EncapsulatedPacket encapsulated : datagram.packets) {
            if (encapsulated.reliability.isReliable()) {
                int missed = encapsulated.reliabilityIndex - this.reliabilityReadIndex & 0xFFFFFF;

                if (missed == 0) {
                    this.reliabilityReadIndex = this.reliabilityReadIndex + 1 & 0xFFFFFF;
                    if (!this.reliableDatagramQueue.isEmpty()) {
                        this.reliableDatagramQueue.poll();
                    }
                } else {
                    if (missed > 0x7FFFFF) {
                        continue;
                    }

                    if (missed < this.reliableDatagramQueue.size()) {
                        if (this.reliableDatagramQueue.get(missed)) {
                            this.reliableDatagramQueue.set(missed, false);
                        } else {
                            // Duplicate packet
                            continue;
                        }
                    } else {
                        if (missed > MAXIMUM_RELIABILITY_GAP) {
                            log.debug("Reliability gap too high from {}: {}", this.address, missed);
                            this.disconnect(DisconnectReason.BAD_PACKET);
                            return;
                        }

                        int count = (missed - this.reliableDatagramQueue.size());
                        for (int i = 0; i < count; i++) {
                            this.reliableDatagramQueue.add(true);
                        }

                        this.reliableDatagramQueue.add(false);
                    }
                }

                while (!this.reliableDatagramQueue.isEmpty() && !this.reliableDatagramQueue.peek()) {
                    this.reliableDatagramQueue.poll();
                    this.reliabilityReadIndex = this.reliabilityReadIndex + 1 & 0xFFFFFF;
                }
                this.reliableDatagramQueue.compact();

                if (missed > 100) {
                    ackImmediate = true;
                }
            }


            if (encapsulated.split) {
                final EncapsulatedPacket reassembled = this.getReassembledPacket(encapsulated);
                if (reassembled == null) {
                    if (this.isClosingOrClosed()) {
                        return;
                    }
                    // Not reassembled
                    continue;
                }
                ackImmediate = true;
                try {
                    if (!this.checkForOrdered(reassembled)) {
                        return;
                    }
                } finally {
                    reassembled.release();
                }
            } else {
                if (!this.checkForOrdered(encapsulated)) {
                    return;
                }
            }
        }

        IntRange range = new IntRange(datagram.sequenceIndex, datagram.sequenceIndex);
        if (!ackImmediate || !this.writeAcknowledge(range, false)) {
            this.offerAcknowledge(range, false);
        }
    }

    private boolean isEncapsulatedPacketValid(EncapsulatedPacket packet) {
        RakNetReliability reliability = packet.getReliability();
        int orderingChannel = packet.getOrderingChannel();
        if ((reliability.isOrdered() || reliability.isSequenced()) && (orderingChannel < 0 || orderingChannel >= MAXIMUM_ORDERING_CHANNELS)) {
            if (log.isDebugEnabled()) {
                log.debug("Received invalid ordering channel {} from {}", orderingChannel, this.address);
            }
            return false;
        }

        if (packet.isSplit() && (!reliability.isReliable() || packet.getPartCount() < 2 || packet.getPartCount() > MAXIMUM_SPLIT_PACKET_COUNT || packet.getPartIndex() < 0 || packet.getPartIndex() >= packet.getPartCount())) {
            if (log.isDebugEnabled()) {
                log.debug("Received invalid split packet metadata from {}: count={}, index={}", this.address, packet.getPartCount(), packet.getPartIndex());
            }
            return false;
        }
        return true;
    }

    private void offerMissingDatagrams(int sequenceIndex, int missedDatagrams) {
        if (missedDatagrams <= 0) {
            return;
        }

        int start = sequenceIndex - missedDatagrams & 0xFFFFFF;
        int end = sequenceIndex - 1 & 0xFFFFFF;
        if (start <= end) {
            this.offerAcknowledge(new IntRange(start, end), true);
        } else {
            this.offerAcknowledge(new IntRange(start, 0xFFFFFF), true);
            this.offerAcknowledge(new IntRange(0, end), true);
        }
    }

    private void offerAcknowledge(IntRange range, boolean nack) {
        if (nack) {
            this.outgoingNaks.offer(range);
        } else {
            this.outgoingAcks.offer(range);
        }
    }

    private int flushAcknowledge(Queue<IntRange> range, boolean nack) {
        final int mtu = this.adjustedMtu - RAKNET_DATAGRAM_HEADER_SIZE;

        int written = 0;
        while (!range.isEmpty()) {
            ByteBuf buffer = this.allocateBuffer(mtu);
            buffer.writeByte(FLAG_VALID | (nack ? FLAG_NACK : FLAG_ACK));
            written += RakNetUtils.writeIntRanges(buffer, range, mtu - 1);
            this.sendDirect(buffer);
        }

        if (written > 0 && !nack) {
            this.slidingWindow.onSendAck();
        }
        return written;
    }

    private boolean writeAcknowledge(IntRange range, boolean nack) {
        final int mtu = this.adjustedMtu - RAKNET_DATAGRAM_HEADER_SIZE;

        ByteBuf buffer = this.allocateBuffer(mtu);
        buffer.writeByte(FLAG_VALID | (nack ? FLAG_NACK : FLAG_ACK));
        buffer.writeShort(1);
        if (range.start == range.end) {
            if (mtu < 4) {
                return false;
            }
            buffer.writeBoolean(true);
            buffer.writeMediumLE(range.start);
        } else {
            if (mtu < 7) {
                return false;
            }
            buffer.writeBoolean(false);
            buffer.writeMediumLE(range.start);
            buffer.writeMediumLE(range.end);
        }
        this.sendDirect(buffer);

        RakMetrics metrics = this.getRakNet().getMetrics();
        if (metrics != null) {
            if (nack) {
                metrics.nackOut(range.end - range.start + 1);
            } else {
                metrics.ackOut(range.end - range.start + 1);
            }
        }

        if (!nack) {
            this.slidingWindow.onSendAck();
        }
        return true;
    }

    private boolean checkForOrdered(EncapsulatedPacket packet) {
        if (packet.getReliability().isOrdered()) {
            return this.onOrderedReceived(packet);
        } else {
            this.onEncapsulatedInternal(packet);
            return !this.isClosingOrClosed();
        }
    }

    private boolean onOrderedReceived(EncapsulatedPacket packet) {
        FastBinaryMinHeap binaryHeap = this.orderingHeaps[packet.orderingChannel];

        int thisIndex = this.orderReadIndex[packet.orderingChannel];
        int packetIndex = packet.orderingIndex;
        if (RakNetUtils.isSequenceIndexAhead(packetIndex, thisIndex)) {
            int orderingGap = RakNetUtils.getSequenceIndexDelta(packetIndex, thisIndex);
            if (orderingGap > MAXIMUM_ORDERING_GAP) {
                log.debug("Ordering gap too high from {}: {}", this.address, orderingGap);
                this.disconnect(DisconnectReason.BAD_PACKET);
                return false;
            }
            long packetBytes = packet.buffer.readableBytes();
            if (this.pendingOrderedPackets >= MAXIMUM_ORDERED_PACKET_COUNT || this.pendingOrderedBytes + packetBytes > MAXIMUM_PENDING_BYTES) {
                log.debug("Ordered packet queue too long for {}", this.address);
                this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
                return false;
            }
            long heapPacketIndex = this.orderReadCount[packet.orderingChannel] + orderingGap;
            binaryHeap.insert(heapPacketIndex, packet.retain());
            this.pendingOrderedPackets++;
            this.pendingOrderedBytes += packetBytes;
            return true;
        }
        if (thisIndex != packetIndex) {
            return true;
        }
        this.orderReadIndex[packet.orderingChannel] = thisIndex + 1 & 0xFFFFFF;
        this.orderReadCount[packet.orderingChannel]++;

        // Can be handled
        this.onEncapsulatedInternal(packet);
        if (this.isClosingOrClosed()) {
            return false;
        }

        EncapsulatedPacket queuedPacket;
        while ((queuedPacket = binaryHeap.peek()) != null) {
            if (queuedPacket.orderingIndex == this.orderReadIndex[packet.orderingChannel]) {
                try {
                    // We got the expected packet
                    binaryHeap.remove();
                    this.removePendingOrderedPacket(queuedPacket);
                    this.orderReadIndex[packet.orderingChannel] = this.orderReadIndex[packet.orderingChannel] + 1 & 0xFFFFFF;
                    this.orderReadCount[packet.orderingChannel]++;

                    this.onEncapsulatedInternal(queuedPacket);
                } finally {
                    queuedPacket.release();
                }
                if (this.isClosingOrClosed()) {
                    return false;
                }
            } else if (!RakNetUtils.isSequenceIndexAhead(queuedPacket.orderingIndex, this.orderReadIndex[packet.orderingChannel])) {
                binaryHeap.remove();
                this.removePendingOrderedPacket(queuedPacket);
                queuedPacket.release();
            } else {
                // Found a gap. Wait till we start receive another ordered packet.
                break;
            }
        }
        return true;
    }

    private void removePendingOrderedPacket(EncapsulatedPacket packet) {
        this.pendingOrderedPackets--;
        this.pendingOrderedBytes -= packet.buffer.readableBytes();
    }

    final void onTick(long curTime) {
        if (this.isClosingOrClosed()) {
            return;
        }
        this.tick(curTime);
    }

    protected void tick(long curTime) {
        if (this.isTimedOut(curTime)) {
            this.close(DisconnectReason.TIMED_OUT);
            return;
        }

        if (this.state == null || this.state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
            return;
        }

        if (this.currentPingTime + 10000L < curTime) {
            this.sendConnectedPing(curTime);
        }

        this.handleIncomingAcknowledge(curTime, this.incomingAcks, false);
        this.handleIncomingAcknowledge(curTime, this.incomingNaks, true);

        // Send known outgoing acknowledge packets.
        int writtenAcks = this.flushAcknowledge(this.outgoingAcks, false);
        int writtenNacks = this.flushAcknowledge(this.outgoingNaks, true);

        RakMetrics metrics = this.getRakNet().getMetrics();
        if (metrics != null) {
            if (writtenNacks > 0) {
                metrics.nackOut(writtenNacks);
            }
            if (writtenAcks > 0) {
                metrics.ackOut(writtenAcks);
            }
        }

        boolean isContinuousSend = this.bandwidthExceededStatistic;
        this.bandwidthExceededStatistic = !this.outgoingPackets.isEmpty();

        // Send packets that are stale first
        if (!this.sendStaleDatagrams(curTime)) {
            return;
        }
        // Now send usual packets
        this.sendDatagrams(curTime, isContinuousSend);
        // Finally flush channel
        this.channel.flush();
    }

    private void handleIncomingAcknowledge(long curTime, Queue<AcknowledgeRange> queue, boolean nack) {
        if (queue.isEmpty()) {
            return;
        }

        List<RakNetDatagram> acknowledgedDatagrams = new ArrayList<>();
        long writeCount = this.datagramWriteCount;
        AcknowledgeRange range;
        AcknowledgeRange firstInvalidRange = null;
        int invalidRangeCount = 0;
        while ((range = queue.poll()) != null) {
            if (this.pendingAcknowledgeRanges > 0) {
                this.pendingAcknowledgeRanges--;
            } else {
                log.warn("Pending acknowledge range counter underflow for {}", this.address);
            }
            if (writeCount < range.writeCount || writeCount - range.writeCount >= MAXIMUM_ACKNOWLEDGE_DISTANCE || !isAcknowledgeRangeValid(range.start, range.end, writeCount)) {
                if (firstInvalidRange == null) {
                    firstInvalidRange = range;
                }
                invalidRangeCount++;
                continue;
            }

            for (Map.Entry<Integer, RakNetDatagram> entry : this.sentDatagrams.subMap(range.start, true, range.end, true).entrySet()) {
                if (this.sentDatagrams.remove(entry.getKey(), entry.getValue())) {
                    acknowledgedDatagrams.add(entry.getValue());
                }
            }
        }
        if (firstInvalidRange != null) {
            this.logOutOfRangeAcknowledge(firstInvalidRange.start, firstInvalidRange.end, writeCount, invalidRangeCount, nack);
        }

        if (acknowledgedDatagrams.isEmpty()) {
            return;
        }

        int processedDatagrams = 0;
        try {
            if (nack) {
                this.slidingWindow.onNak();
            }
            while (processedDatagrams < acknowledgedDatagrams.size()) {
                RakNetDatagram datagram = acknowledgedDatagrams.get(processedDatagrams++);
                if (nack) {
                    this.onIncomingNack(datagram, curTime);
                } else {
                    this.onIncomingAck(datagram, curTime);
                }
            }
        } finally {
            while (processedDatagrams < acknowledgedDatagrams.size()) {
                ReferenceCountUtil.safeRelease(acknowledgedDatagrams.get(processedDatagrams++));
            }
        }
    }

    private static boolean isAcknowledgeRangeValid(int start, int end, long writeCount) {
        if (start < 0 || end < start || end > SEQUENCE_INDEX_MASK || writeCount <= 0) {
            return false;
        }

        long lastSent = writeCount - 1;
        long absoluteEnd = (lastSent & ~SEQUENCE_INDEX_MASK) | end;
        if (absoluteEnd > lastSent) {
            absoluteEnd -= SEQUENCE_INDEX_COUNT;
        }
        long absoluteStart = absoluteEnd - ((long) end - start);
        return absoluteStart >= 0 && lastSent - absoluteStart < MAXIMUM_ACKNOWLEDGE_DISTANCE;
    }

    private void logOutOfRangeAcknowledge(int start, int end, long writeCount, int count, boolean nack) {
        if (log.isDebugEnabled()) {
            log.debug("Received {} with {} out-of-range entries from {} (first range: [{}, {}], write count: {})",
                    nack ? "NACK" : "ACK", count, this.address, start, end, writeCount);
        }
    }

    private void onIncomingAck(RakNetDatagram datagram, long curTime) {
        try {
            this.unackedBytes -= datagram.getSize();
            this.slidingWindow.onAck(curTime - datagram.sendTime, datagram.sequenceIndex, this.bandwidthExceededStatistic);
        } finally {
            datagram.release();
        }
    }

    private void onIncomingNack(RakNetDatagram datagram, long curTime) {
        boolean resendStarted = false;
        try {
            if (log.isTraceEnabled()) {
                log.trace("NAK'ed datagram {} from {}", datagram.sequenceIndex, this.address);
            }
            datagram.isContinuousSend = false;
            resendStarted = true;
            this.sendDatagram(datagram, curTime);
        } finally {
            if (!resendStarted) {
                datagram.release();
            }
        }
    }

    private boolean sendStaleDatagrams(long curTime) {
        if (this.sentDatagrams.isEmpty()) {
            return true;
        }

        int resendCount = 0;
        int transmissionBandwidth = this.slidingWindow.getRetransmissionBandwidth(this.unackedBytes);

        List<RakNetDatagram> resend = new ArrayList<>(Math.min(this.sentDatagrams.size(), MAXIMUM_STALE_DATAGRAMS + 1));
        for (Map.Entry<Integer, RakNetDatagram> entry : this.sentDatagrams.entrySet()) {
            RakNetDatagram datagram = entry.getValue();
            if (datagram.getNextSend() <= curTime) {
                int size = datagram.getSize();
                if (transmissionBandwidth < size) {
                    break;
                }
                if (!this.sentDatagrams.remove(entry.getKey(), datagram)) {
                    continue;
                }
                transmissionBandwidth -= size;

                resendCount++;
                resend.add(datagram);
                if (resendCount > MAXIMUM_STALE_DATAGRAMS) {
                    break;
                }
            }
        }

        if (resendCount > MAXIMUM_STALE_DATAGRAMS) {
            for (RakNetDatagram datagram : resend) {
                ReferenceCountUtil.safeRelease(datagram);
            }
            log.debug("Too many stale datagrams for {}", this.address);
            this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
            return false;
        }

        boolean isContinuousSend = resend.size() > 1;
        int processedDatagrams = 0;
        try {
            while (processedDatagrams < resend.size()) {
                RakNetDatagram datagram = resend.get(processedDatagrams++);
                datagram.isContinuousSend = isContinuousSend;
                this.sendDatagram(datagram, curTime);
            }
        } finally {
            while (processedDatagrams < resend.size()) {
                ReferenceCountUtil.safeRelease(resend.get(processedDatagrams++));
            }
        }

        if (resendCount > 0) {
            this.slidingWindow.onResend();
        }

        RakMetrics metrics = this.getRakNet().getMetrics();
        if (metrics != null) {
            metrics.rakStaleDatagrams(resendCount);
        }
        return true;
    }

    private void sendDatagrams(long curTime, boolean isContinuousSend) {
        if (this.outgoingPackets.isEmpty()) {
            return;
        }

        int transmissionBandwidth = this.slidingWindow.getTransmissionBandwidth(this.unackedBytes, this.bandwidthExceededStatistic);
        RakNetDatagram datagram = new RakNetDatagram(curTime);
        datagram.isContinuousSend = isContinuousSend;
        EncapsulatedPacket packet;

        while ((packet = this.outgoingPackets.peek()) != null) {
            int size = packet.getSize();
            if (transmissionBandwidth < size) {
                break;
            }

            transmissionBandwidth -= size;
            this.outgoingPackets.remove();
            this.queuedBytes -= size;

            // Send full datagram
            if (!datagram.tryAddPacket(packet, this.adjustedMtu)) {
                this.sendDatagram(datagram, curTime);

                datagram = new RakNetDatagram(curTime);
                datagram.isContinuousSend = isContinuousSend;
                if (!datagram.tryAddPacket(packet, this.adjustedMtu)) {
                    throw new IllegalArgumentException("Packet too large to fit in MTU (size: " + size + ", MTU: " + this.adjustedMtu +")");
                }
            }
        }

        if (!datagram.getPackets().isEmpty()) {
            this.sendDatagram(datagram, curTime);
        }
    }

    @Override
    public void disconnect() {
        this.disconnect(DisconnectReason.DISCONNECTED);
    }

    @Override
    public void disconnect(DisconnectReason reason) {
        if (!this.isClosed() && this.closing.compareAndSet(false, true)) {
            this.scheduleClose(() -> this.disconnect0(reason));
        }
    }

    private void disconnect0(DisconnectReason reason) {
        if (!this.isClosed()) {
            try {
                this.sendDisconnectionNotification();
            } finally {
                this.close0(reason);
            }
        }
    }

    @Override
    public void close() {
        this.close(DisconnectReason.DISCONNECTED);
    }

    @Override
    public void close(DisconnectReason reason) {
        if (!this.isClosed() && this.closing.compareAndSet(false, true)) {
            this.scheduleClose(() -> this.close0(reason));
        }
    }

    private void scheduleClose(Runnable task) {
        try {
            this.eventLoop.execute(task);
        } catch (RuntimeException exception) {
            task.run();
        }
    }

    private void close0(DisconnectReason reason) {
        if (this.isClosed()) {
            return;
        }

        RakNetSessionListener listener = this.listener;
        if (listener != null) {
            try {
                listener.onPreDisconnect(reason);
            } catch (Throwable throwable) {
                log.warn("RakNet session listener failed during onPreDisconnect for {}", this.address, throwable);
            }
        }

        this.closed = true;
        this.state = RakNetState.UNCONNECTED;
        try {
            this.onClose();
        } catch (Throwable throwable) {
            log.warn("RakNet session failed during onClose for {}", this.address, throwable);
        }
        if (log.isTraceEnabled()) {
            log.trace("RakNet Session ({} => {}) closed: {}", this.getRakNet().getBindAddress(), this.address, reason);
        }

        try {
            this.deinitialize();
        } catch (Throwable throwable) {
            log.warn("RakNet session failed during deinitialize for {}", this.address, throwable);
        }
        if (listener != null) {
            try {
                listener.onDisconnect(reason);
            } catch (Throwable throwable) {
                log.warn("RakNet session listener failed during onDisconnect for {}", this.address, throwable);
            }
        }
    }

    protected void onClose() {
    }

    @Override
    public void sendImmediate(ByteBuf buf) {
        this.send(buf, RakNetPriority.IMMEDIATE);
    }

    @Override
    public void send(ByteBuf buf) {
        this.send(buf, RakNetPriority.MEDIUM);
    }

    public void send(ByteBuf buf, RakNetPriority priority) {
        this.send(buf, priority, RakNetReliability.RELIABLE_ORDERED);
    }

    public void send(ByteBuf buf, RakNetReliability reliability) {
        this.send(buf, RakNetPriority.MEDIUM, reliability);
    }

    public void send(ByteBuf buf, RakNetPriority priority, RakNetReliability reliability) {
        this.send(buf, priority, reliability, 0);
    }

    public void send(ByteBuf buf, RakNetPriority priority, RakNetReliability reliability, @Nonnegative int orderingChannel) {
        int checkedOrderingChannel = orderingChannel >= 0 && orderingChannel < MAXIMUM_ORDERING_CHANNELS ? orderingChannel : 0;

        if (this.eventLoop.inEventLoop()) {
            this.send0(buf, priority, reliability, checkedOrderingChannel);
        } else {
            try {
                this.eventLoop.execute(() -> this.send0(buf, priority, reliability, checkedOrderingChannel));
            } catch (RuntimeException exception) {
                buf.release();
                throw exception;
            }
        }
    }

    private void send0(ByteBuf buf, RakNetPriority priority, RakNetReliability reliability, @Nonnegative int orderingChannel) {
        try {
            if (this.isClosed() || state == null || state.ordinal() < RakNetState.INITIALIZED.ordinal()) {
                // Session is not ready for RakNet datagrams.
                return;
            }
            if (!buf.isReadable()) {
                return;
            }
            if (priority != RakNetPriority.IMMEDIATE && buf.readableBytes() > MAXIMUM_PENDING_BYTES) {
                this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
                return;
            }
            int maximumSplitPayloadSize = (this.adjustedMtu - MAXIMUM_ENCAPSULATED_HEADER_SIZE - RAKNET_DATAGRAM_HEADER_SIZE) * MAXIMUM_SPLIT_PACKET_COUNT;
            if (buf.readableBytes() > maximumSplitPayloadSize) {
                this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
                return;
            }
            EncapsulatedPacket[] packets = this.createEncapsulated(buf, priority, reliability, orderingChannel);

            if (priority == RakNetPriority.IMMEDIATE) {
                if (reliability.isReliable()) {
                    long immediateBytes = 0;
                    for (EncapsulatedPacket packet : packets) {
                        immediateBytes += RAKNET_DATAGRAM_HEADER_SIZE + packet.getSize();
                    }
                    if (immediateBytes > MAXIMUM_PENDING_BYTES || this.unackedBytes > MAXIMUM_PENDING_BYTES - immediateBytes) {
                        for (EncapsulatedPacket packet : packets) {
                            packet.release();
                        }
                        this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
                        return;
                    }
                }
                this.sendImmediate(packets);
                return;
            }

            long packetBytes = 0;
            for (EncapsulatedPacket packet : packets) {
                packetBytes += packet.getSize();
            }
            if (packetBytes > MAXIMUM_PENDING_BYTES || this.queuedBytes > MAXIMUM_PENDING_BYTES - packetBytes) {
                for (EncapsulatedPacket packet : packets) {
                    packet.release();
                }
                this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
                return;
            }
            this.queuedBytes += packetBytes;

            long weight = this.getNextWeight(priority);
            if (packets.length == 1) {
                this.outgoingPackets.insert(weight, packets[0]);
            } else {
                this.outgoingPackets.insertSeries(weight, packets);
            }
        } finally {
            buf.release();
        }
    }

    private void sendImmediate(EncapsulatedPacket[] packets) {
        long curTime = System.currentTimeMillis();
        int transferredPackets = 0;
        try {
            for (EncapsulatedPacket packet : packets) {
                RakNetDatagram datagram = new RakNetDatagram(curTime);

                if (!datagram.tryAddPacket(packet, this.adjustedMtu)) {
                    datagram.release();
                    throw new IllegalArgumentException("Packet too large to fit in MTU (size: " + packet.getSize() +
                            ", MTU: " + this.adjustedMtu + ")");
                }
                transferredPackets++;
                this.sendDatagram(datagram, curTime);
            }
        } finally {
            while (transferredPackets < packets.length) {
                ReferenceCountUtil.safeRelease(packets[transferredPackets++]);
            }
        }
        this.channel.flush();
    }

    private EncapsulatedPacket[] createEncapsulated(ByteBuf buffer, RakNetPriority priority, RakNetReliability reliability,
                                                    int orderingChannel) {
        int maxLength = this.adjustedMtu - MAXIMUM_ENCAPSULATED_HEADER_SIZE - RAKNET_DATAGRAM_HEADER_SIZE;

        ByteBuf[] buffers;
        int splitId = 0;

        if (buffer.readableBytes() > maxLength) {
            // Packet requires splitting
            // Adjust reliability
            switch (reliability) {
                case UNRELIABLE:
                    reliability = RakNetReliability.RELIABLE;
                    break;
                case UNRELIABLE_SEQUENCED:
                    reliability = RakNetReliability.RELIABLE_SEQUENCED;
                    break;
                case UNRELIABLE_WITH_ACK_RECEIPT:
                    reliability = RakNetReliability.RELIABLE_WITH_ACK_RECEIPT;
                    break;
            }

            int split = ((buffer.readableBytes() - 1) / maxLength) + 1;
            buffers = new ByteBuf[split];
            int createdBuffers = 0;
            try {
                for (; createdBuffers < split; createdBuffers++) {
                    buffers[createdBuffers] = buffer.readRetainedSlice(Math.min(maxLength, buffer.readableBytes()));
                }
                if (buffer.isReadable()) {
                    throw new IllegalStateException("Buffer still has bytes to read!");
                }
            } catch (RuntimeException | Error throwable) {
                for (int i = 0; i < createdBuffers; i++) {
                    ReferenceCountUtil.safeRelease(buffers[i]);
                }
                throw throwable;
            }

            // Allocate split ID
            splitId = this.allocateSplitId();
        } else {
            buffers = new ByteBuf[]{buffer.readRetainedSlice(buffer.readableBytes())};
        }

        // Set meta
        int orderingIndex = 0;
        /*int sequencingIndex = 0;
        if (reliability.isSequenced()) {
            sequencingIndex = this.sequenceWriteIndex.getAndIncrement(orderingChannel);
        } todo: sequencing */
        if (reliability.isOrdered()) {
            orderingIndex = this.orderWriteIndex[orderingChannel];
            this.orderWriteIndex[orderingChannel] = this.orderWriteIndex[orderingChannel] + 1 & 0xFFFFFF;
        }

        // Now create the packets.
        EncapsulatedPacket[] packets = new EncapsulatedPacket[buffers.length];
        for (int i = 0, parts = buffers.length; i < parts; i++) {
            EncapsulatedPacket packet = new EncapsulatedPacket();
            packet.buffer = buffers[i];
            packet.orderingChannel = (short) orderingChannel;
            packet.orderingIndex = orderingIndex;
            //packet.setSequenceIndex(sequencingIndex);
            packet.reliability = reliability;
            packet.priority = priority;
            if (reliability.isReliable()) {
                packet.reliabilityIndex = -1;
            }

            if (parts > 1) {
                packet.split = true;
                packet.partIndex = i;
                packet.partCount = parts;
                packet.partId = splitId;
            }

            packets[i] = packet;
        }
        return packets;
    }

    private int allocateSplitId() {
        int splitId = this.splitIndex;
        this.splitIndex = (this.splitIndex + 1) & SPLIT_ID_MASK;
        return splitId;
    }

    private void sendDatagram(RakNetDatagram datagram, long time) {
        try {
            if (this.isClosingOrClosed()) {
                return;
            }
            Preconditions.checkArgument(!datagram.packets.isEmpty(), "RakNetDatagram with no packets");
            boolean reliable = false;
            for (EncapsulatedPacket packet : datagram.packets) {
                if (packet.reliability.isReliable()) {
                    reliable = true;
                    if (packet.reliabilityIndex < 0) {
                        packet.reliabilityIndex = this.reliabilityWriteIndex;
                        this.reliabilityWriteIndex = this.reliabilityWriteIndex + 1 & 0xFFFFFF;
                    }
                }
            }

            int oldIndex = datagram.sequenceIndex;
            datagram.sequenceIndex = this.slidingWindow.getAndIncrementNextSequenceNumber();
            this.datagramWriteCount++;

            if (reliable) {
                datagram.nextSend = time + this.slidingWindow.getRtoForRetransmission();
                if (oldIndex != -1) {
                    this.sentDatagrams.remove(oldIndex, datagram);
                }
                RakNetDatagram retainedDatagram = datagram.retain();
                if (this.sentDatagrams.putIfAbsent(datagram.sequenceIndex, retainedDatagram) != null) {
                    retainedDatagram.release();
                    log.debug("Too many unacknowledged datagrams for {}", this.address);
                    this.disconnect(DisconnectReason.QUEUE_TOO_LONG);
                    return;
                }
                if (oldIndex == -1) {
                    this.unackedBytes += datagram.getSize();
                }
            }

            RakMetrics metrics = this.getRakNet().getMetrics();
            if (metrics != null) {
                metrics.rakDatagramsOut(1);
            }
            ByteBuf buf = this.allocateBuffer(datagram.getSize());
            boolean transferred = false;
            try {
                Preconditions.checkArgument(buf.writableBytes() < this.adjustedMtu, "Packet length was %s but expected %s", buf.writableBytes(), this.adjustedMtu);
                datagram.encode(buf);
                this.channel.write(new DatagramPacket(buf, this.address));
                transferred = true;
            } finally {
                if (!transferred) {
                    ReferenceCountUtil.safeRelease(buf);
                }
            }
        } finally {
            datagram.release();
        }
    }

    void sendDirect(ByteBuf buffer) {
        boolean transferred = false;
        try {
            this.channel.writeAndFlush(new DatagramPacket(buffer, this.address));
            transferred = true;
        } finally {
            if (!transferred) {
                ReferenceCountUtil.safeRelease(buffer);
            }
        }
    }

    public int getSessionTimeout(){
        return sessionTimeout;
    }

    /** timeout in ms ( 1 second = 1000 ) **/
    public void setSessionTimeout(int timeout){
        this.sessionTimeout = timeout;
    }

    /*
     * Packet Handlers
     */

    private boolean onAcknowledge(ByteBuf buffer, Queue<AcknowledgeRange> queue, boolean nack) {
        this.checkForClosed();
        if (buffer.readableBytes() < Short.BYTES) {
            this.disconnectMalformedAcknowledge(nack);
            return false;
        }

        int size = buffer.readUnsignedShort();
        if (size > buffer.readableBytes() / 4) {
            this.disconnectMalformedAcknowledge(nack);
            return false;
        }

        long writeCount = this.datagramWriteCount;
        int rangesIndex = buffer.readerIndex();
        int rangeIndex = rangesIndex;
        int writerIndex = buffer.writerIndex();
        long length = 0;
        int firstInvalidStart = 0;
        int firstInvalidEnd = 0;
        int invalidRangeCount = 0;
        int validRangeCount = 0;
        for (int i = 0; i < size; i++) {
            if (writerIndex - rangeIndex < 4) {
                this.disconnectMalformedAcknowledge(nack);
                return false;
            }
            boolean singleton = buffer.getBoolean(rangeIndex);
            int start = buffer.getUnsignedMediumLE(rangeIndex + 1);
            rangeIndex += 4;
            int end = start;
            if (!singleton) {
                if (writerIndex - rangeIndex < 3) {
                    this.disconnectMalformedAcknowledge(nack);
                    return false;
                }
                end = buffer.getUnsignedMediumLE(rangeIndex);
                rangeIndex += 3;
            }
            if (start > end) {
                if (log.isTraceEnabled()) {
                    log.trace("{} sent an IntRange with a start value {} greater than an end value of {}", this.address,
                            start, end);
                }
                this.disconnect(DisconnectReason.BAD_PACKET);
                return false;
            }
            if (!isAcknowledgeRangeValid(start, end, writeCount)) {
                if (invalidRangeCount == 0) {
                    firstInvalidStart = start;
                    firstInvalidEnd = end;
                }
                invalidRangeCount++;
                continue;
            }
            validRangeCount++;
            length = Math.min(Integer.MAX_VALUE, length + end - start + 1L);
        }

        if (validRangeCount > MAXIMUM_PENDING_ACKNOWLEDGE_RANGES - this.pendingAcknowledgeRanges) {
            this.disconnectMalformedAcknowledge(nack);
            return false;
        }

        int rangesEndIndex = rangeIndex;
        rangeIndex = rangesIndex;
        int queuedRangeCount = 0;
        this.pendingAcknowledgeRanges += validRangeCount;
        try {
            for (int i = 0; i < size; i++) {
                boolean singleton = buffer.getBoolean(rangeIndex);
                int start = buffer.getUnsignedMediumLE(rangeIndex + 1);
                rangeIndex += 4;
                int end = start;
                if (!singleton) {
                    end = buffer.getUnsignedMediumLE(rangeIndex);
                    rangeIndex += 3;
                }
                if (isAcknowledgeRangeValid(start, end, writeCount)) {
                    queue.add(new AcknowledgeRange(start, end, writeCount));
                    queuedRangeCount++;
                }
            }
        } finally {
            this.pendingAcknowledgeRanges -= validRangeCount - queuedRangeCount;
        }
        buffer.readerIndex(rangesEndIndex);
        if (invalidRangeCount > 0) {
            this.logOutOfRangeAcknowledge(firstInvalidStart, firstInvalidEnd, writeCount, invalidRangeCount, nack);
        }

        RakMetrics metrics = this.getRakNet().getMetrics();
        if (metrics != null) {
            int metricLength = (int) length;
            if (nack) {
                metrics.nackIn(metricLength);
            } else {
                metrics.ackIn(metricLength);
            }
        }
        return true;
    }

    private void disconnectMalformedAcknowledge(boolean nack) {
        if (log.isDebugEnabled()) {
            log.debug("Received malformed {} from {}", nack ? "NACK" : "ACK", this.address);
        }
        this.disconnect(DisconnectReason.BAD_PACKET);
    }

    private void onConnectedPing(ByteBuf buffer) {
        if (buffer.readableBytes() != Long.BYTES) {
            this.disconnect(DisconnectReason.BAD_PACKET);
            return;
        }
        long pingTime = buffer.readLong();
        this.sendConnectedPong(pingTime);
    }

    private void onConnectedPong(ByteBuf buffer) {
        if (buffer.readableBytes() != Long.BYTES * 2) {
            this.disconnect(DisconnectReason.BAD_PACKET);
            return;
        }
        long pingTime = buffer.readLong();
        buffer.readLong();
        if (this.currentPingTime == pingTime) {
            this.lastPingTime = this.currentPingTime;
            this.lastPongTime = System.currentTimeMillis();
        }
    }

    private void onDisconnectionNotification() {
        this.close(DisconnectReason.CLOSED_BY_REMOTE_PEER);
    }

    /*
        Packet Dispatchers
     */

    private void sendConnectedPing(long pingTime) {
        ByteBuf buffer = this.allocateBuffer(9);
        buffer.writeByte(ID_CONNECTED_PING);
        buffer.writeLong(pingTime);
        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.UNRELIABLE);
        this.currentPingTime = pingTime;
    }

    private void sendConnectedPong(long pingTime) {
        ByteBuf buffer = this.allocateBuffer(17);
        buffer.writeByte(ID_CONNECTED_PONG);
        buffer.writeLong(pingTime);
        buffer.writeLong(System.currentTimeMillis());
        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.UNRELIABLE);
    }

    private void sendDisconnectionNotification() {
        ByteBuf buffer = this.allocateBuffer(1);
        buffer.writeByte(ID_DISCONNECTION_NOTIFICATION);
        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE);
    }

    private void sendDetectLostConnection() {
        ByteBuf buffer = this.allocateBuffer(1);
        buffer.writeByte(ID_DETECT_LOST_CONNECTION);
        this.send(buffer, RakNetPriority.IMMEDIATE);
    }

    protected final void touch() {
        this.checkForClosed();
        this.lastTouched = System.currentTimeMillis();
    }

    public boolean isStale(long curTime) {
        return curTime - this.lastTouched >= SESSION_STALE_MS;
    }

    public boolean isStale() {
        return isStale(System.currentTimeMillis());
    }

    public boolean isTimedOut(long curTime) {
        return curTime - this.lastTouched >= this.sessionTimeout ||
                this.state != RakNetState.CONNECTED && curTime - this.createdAt >= this.sessionTimeout;
    }

    public boolean isTimedOut() {
        return isTimedOut(System.currentTimeMillis());
    }


    private void checkForClosed() {
        Preconditions.checkState(!this.isClosed(), "Session already closed");
    }

    public boolean isClosed() {
        return this.closed;
    }

    final boolean isClosingOrClosed() {
        return this.closed || this.closing.get();
    }

    public abstract RakNet getRakNet();

    boolean isIpv6Session() {
        return this.address.getAddress() instanceof Inet6Address;
    }

    public RakNetState getState() {
        return state;
    }

    void setState(@Nullable RakNetState state) {
        if (this.state != state) {
            this.state = state;
            if (this.listener != null) {
                this.listener.onSessionChangeState(this.state);
            }
        }
    }

    public Channel getChannel() {
        return channel;
    }

    public EventLoop getEventLoop() {
        return eventLoop;
    }

    private static final class AcknowledgeRange extends IntRange {
        private final long writeCount;

        private AcknowledgeRange(int start, int end, long writeCount) {
            super(start, end);
            this.writeCount = writeCount;
        }
    }
}
