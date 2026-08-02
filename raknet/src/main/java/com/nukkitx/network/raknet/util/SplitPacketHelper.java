package com.nukkitx.network.raknet.util;

import com.nukkitx.network.raknet.EncapsulatedPacket;
import com.nukkitx.network.raknet.RakNetReliability;
import com.nukkitx.network.raknet.RakNetSession;
import com.nukkitx.network.util.Preconditions;
import io.netty.buffer.ByteBuf;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

import javax.annotation.Nullable;

import static com.nukkitx.network.raknet.RakNetConstants.*;

public class SplitPacketHelper extends AbstractReferenceCounted {
    private static final long IDLE_TIMEOUT_NANOS = 30_000_000_000L; // TimeUnit.SECONDS.toNanos(30)

    private final int partId;
    private final int partCount;
    private final EncapsulatedPacket[] packets;
    private long lastPartTimeNanos = System.nanoTime();
    private RakNetReliability reliability;
    private short orderingChannel;
    private int orderingIndex;
    private int sequenceIndex;
    private int receivedPartCount;
    private long totalSize;

    public SplitPacketHelper(int partId, int partCount) {
        Preconditions.checkArgument(partId >= 0 && partId <= SPLIT_ID_MASK,
                "partId is less than 0 or greater than %s (%s)", SPLIT_ID_MASK, partId);
        Preconditions.checkArgument(partCount >= 2 && partCount <= MAXIMUM_SPLIT_PACKET_COUNT,
                "partCount is less than 2 or greater than %s (%s)", MAXIMUM_SPLIT_PACKET_COUNT, partCount);
        this.partId = partId;
        this.partCount = partCount;
        this.packets = new EncapsulatedPacket[partCount];
    }

    public int getPartId() {
        return this.partId;
    }

    public int getPartCount() {
        return this.partCount;
    }

    public int getReceivedPartCount() {
        return this.receivedPartCount;
    }

    public long getTotalSize() {
        return this.totalSize;
    }

    public boolean isCompatible(EncapsulatedPacket packet) {
        Preconditions.checkNotNull(packet, "packet");
        Preconditions.checkState(this.refCnt() > 0, "packet has been released");
        if (!packet.isSplit() || packet.getPartId() != this.partId || packet.getPartCount() != this.partCount ||
                packet.getPartIndex() < 0 || packet.getPartIndex() >= this.partCount) {
            return false;
        }

        if (this.receivedPartCount > 0 && (packet.getReliability() != this.reliability ||
                packet.getOrderingChannel() != this.orderingChannel || packet.getOrderingIndex() != this.orderingIndex ||
                packet.getSequenceIndex() != this.sequenceIndex)) {
            return false;
        }

        int partIndex = packet.getPartIndex();
        if (this.packets[partIndex] != null) {
            return true;
        }
        return this.totalSize + (long) packet.getBuffer().readableBytes() <= Integer.MAX_VALUE;
    }

    @Nullable
    public EncapsulatedPacket add(EncapsulatedPacket packet, RakNetSession session) {
        Preconditions.checkNotNull(packet, "packet");
        Preconditions.checkArgument(packet.isSplit(), "packet is not split");
        Preconditions.checkState(this.refCnt() > 0, "packet has been released");
        if (!this.isCompatible(packet)) {
            return null;
        }

        int partIndex = packet.getPartIndex();
        if (this.packets[partIndex] != null) {
            // Duplicate
            return null;
        }

        if (this.receivedPartCount == 0) {
            this.reliability = packet.getReliability();
            this.orderingChannel = packet.getOrderingChannel();
            this.orderingIndex = packet.getOrderingIndex();
            this.sequenceIndex = packet.getSequenceIndex();
        }

        // Retain the packet so it can be reassembled later.
        this.packets[partIndex] = packet.retain();
        this.receivedPartCount++;
        this.totalSize += packet.getBuffer().readableBytes();
        this.lastPartTimeNanos = System.nanoTime();

        if (this.receivedPartCount != this.partCount) {
            return null;
        }

        // We can't use a composite buffer as the native code will choke on it
        ByteBuf reassembled = session.allocateBuffer((int) this.totalSize);
        boolean transferred = false;
        try {
            for (EncapsulatedPacket netPacket : this.packets) {
                ByteBuf buf = netPacket.getBuffer();
                reassembled.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
            }

            EncapsulatedPacket reassembledPacket = this.packets[0].fromSplit(reassembled);
            transferred = true;
            return reassembledPacket;
        } finally {
            if (!transferred) {
                reassembled.release();
            }
        }
    }

    public boolean expired() {
        // If we're waiting on a split packet for more than 30 seconds, the client on the other end is either severely
        // lagging, or has died.
        Preconditions.checkState(this.refCnt() > 0, "packet has been released");
        return System.nanoTime() - this.lastPartTimeNanos >= IDLE_TIMEOUT_NANOS;
    }

    @Override
    protected void deallocate() {
        for (EncapsulatedPacket packet : this.packets) {
            ReferenceCountUtil.release(packet);
        }
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        throw new UnsupportedOperationException();
    }
}
