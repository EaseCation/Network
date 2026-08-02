package com.nukkitx.network.raknet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.nukkitx.network.raknet.RakNetConstants.*;

@ParametersAreNonnullByDefault
public class RakNetServerSession extends RakNetSession {
    private static final int IPV4_CONNECTION_REQUEST_ACCEPTED_SIZE = 166;
    private static final int IPV6_CONNECTION_REQUEST_ACCEPTED_SIZE = 210;

    private final RakNetServer rakNet;
    @Nullable
    private ScheduledFuture<?> tickFuture;
    private final Object tickFutureLock = new Object();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean sessionQuota = new AtomicBoolean();
    @Nullable
    private InetAddress sessionQuotaAddress;

    RakNetServerSession(RakNetServer rakNet, InetSocketAddress remoteAddress, Channel channel, EventLoop eventLoop, int mtu,
                        int protocolVersion) {
        super(remoteAddress, channel, eventLoop, mtu, protocolVersion);
        this.rakNet = rakNet;
    }

    @Override
    protected void onPacket(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return;
        }

        short packetId = buffer.readUnsignedByte();
        boolean handled = false;
        switch (packetId) {
            case ID_OPEN_CONNECTION_REQUEST_2:
                handled = this.onOpenConnectionRequest2(buffer);
                break;
            case ID_CONNECTION_REQUEST:
                handled = this.onConnectionRequest(buffer);
                break;
            case ID_NEW_INCOMING_CONNECTION:
                handled = this.onNewIncomingConnection(buffer);
                break;
        }
        if (handled) {
            this.touch();
        }
    }

    @Override
    protected void onClose() {
        synchronized (this.tickFutureLock) {
            ScheduledFuture<?> tickFuture = this.tickFuture;
            if (tickFuture != null) {
                tickFuture.cancel(false);
                this.tickFuture = null;
            }
        }
        boolean removed;
        synchronized (this.rakNet.getSessionCreationLock(this.address)) {
            try {
                this.releaseSessionReservations();
            } finally {
                removed = this.rakNet.sessionsByAddress.remove(this.address, this);
                InetSocketAddress proxiedAddress = this.proxiedAddress;
                if (proxiedAddress != null) {
                    this.rakNet.removeProxiedAddress(this.address, proxiedAddress);
                }
            }
        }
        if (!removed) {
            throw new IllegalStateException("Session was not found in session map");
        }
    }

    @Override
    public RakNet getRakNet() {
        return this.rakNet;
    }

    private void onTick() {
        long curTime = System.currentTimeMillis();
        try {
            this.onTick(curTime);
        } catch (Exception e) {
            log.error("RakNet server tick exception", e);
            this.close(DisconnectReason.DISCONNECTED);
        }
    }

    private boolean onOpenConnectionRequest2(ByteBuf buffer) {
        RakNetState state = this.getState();
        if (state != RakNetState.INITIALIZING && state != RakNetState.INITIALIZED) {
            return false;
        }

        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return false;
        }

        if (!NetworkUtils.skipAddress(buffer) || !buffer.isReadable(Short.BYTES + Long.BYTES)) {
            return false;
        }

        int mtu = buffer.readUnsignedShort();
        if (mtu < MINIMUM_MTU_SIZE || mtu > MAXIMUM_MTU_SIZE) {
            return false;
        }
        long guid = buffer.readLong();

        if (state == RakNetState.INITIALIZED) {
            if (mtu != this.getMtu() || guid != this.guid) {
                return false;
            }
            try {
                this.sendOpenConnectionReply2();
            } catch (RuntimeException | Error throwable) {
                this.close(DisconnectReason.DISCONNECTED);
                throw throwable;
            }
            return true;
        }

        try {
            this.setMtu(mtu);
            this.guid = guid;

            // We can now accept RakNet datagrams.
            this.initialize();

            sendOpenConnectionReply2();
            this.setState(RakNetState.INITIALIZED);
            this.rakNet.notifySessionCreation(this);
        } catch (RuntimeException | Error throwable) {
            this.close(DisconnectReason.DISCONNECTED);
            throw throwable;
        }
        return true;
    }

    private boolean onConnectionRequest(ByteBuf buffer) {
        if (this.getState() != RakNetState.INITIALIZED) {
            return false;
        }
        if (!buffer.isReadable(Long.BYTES * 2 + 1)) {
            return false;
        }

        long guid = buffer.readLong();
        long time = buffer.readLong();
        boolean security = buffer.readBoolean();

        if (this.guid != guid || security) {
            this.sendConnectionFailure(ID_CONNECTION_REQUEST_FAILED);
            this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
            return false;
        }

        this.setState(RakNetState.CONNECTING);

        this.sendConnectionRequestAccepted(time);
        return true;
    }

    private boolean onNewIncomingConnection(ByteBuf buffer) {
        if (this.getState() != RakNetState.CONNECTING) {
            return false;
        }
        if (!NetworkUtils.skipAddress(buffer) || !buffer.isReadable(Long.BYTES * 2)) {
            return false;
        }

        buffer.readerIndex(buffer.writerIndex() - Long.BYTES * 2);
        buffer.readLong();
        buffer.readLong();
        try {
            this.setState(RakNetState.CONNECTED);
            this.releasePendingSession();
        } catch (RuntimeException | Error throwable) {
            this.close(DisconnectReason.DISCONNECTED);
            throw throwable;
        }
        return true;
    }

    private synchronized void releasePendingSession() {
        if (this.pending.compareAndSet(true, false)) {
            this.rakNet.releasePendingSession();
        }
    }

    void registerPendingSession() {
        if (!this.pending.compareAndSet(false, true)) {
            throw new IllegalStateException("Session is already pending");
        }
    }

    void registerSessionQuota(InetAddress address) {
        if (!this.sessionQuota.compareAndSet(false, true)) {
            throw new IllegalStateException("Session quota is already registered");
        }
        this.sessionQuotaAddress = address;
    }

    synchronized void releaseSessionReservations() {
        boolean releasePending = this.pending.compareAndSet(true, false);
        if (this.sessionQuota.compareAndSet(true, false)) {
            InetAddress address = this.sessionQuotaAddress;
            this.sessionQuotaAddress = null;
            if (address == null) {
                throw new IllegalStateException("Session quota address is missing");
            }
            this.rakNet.releaseSession(address, releasePending);
        } else if (releasePending) {
            this.rakNet.releasePendingSession();
        }
    }

    void sendOpenConnectionReply1() {
        synchronized (this.tickFutureLock) {
            if (this.isClosed()) {
                return;
            }
            ScheduledFuture<?> tickFuture = this.tickFuture;
            if (tickFuture == null) {
                this.tickFuture = this.eventLoop.scheduleAtFixedRate(this::onTick, 0, 10, TimeUnit.MILLISECONDS);
            }
        }

        ByteBuf buffer = this.allocateBuffer(28);

        buffer.writeByte(ID_OPEN_CONNECTION_REPLY_1);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.rakNet.guid);
        buffer.writeBoolean(false); // Security
        buffer.writeShort(this.getMtu());

        this.sendDirect(buffer);
    }

    private void sendOpenConnectionReply2() {
        ByteBuf buffer = this.allocateBuffer(31);

        buffer.writeByte(ID_OPEN_CONNECTION_REPLY_2);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.rakNet.guid);
        NetworkUtils.writeAddress(buffer, this.address);
        buffer.writeShort(this.getMtu());
        buffer.writeBoolean(false); // Security

        this.sendDirect(buffer);
    }

    private void sendConnectionFailure(short id) {
        ByteBuf buffer = this.allocateBuffer(21);
        buffer.writeByte(id);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.rakNet.guid);

        this.sendDirect(buffer);
    }

    private void sendConnectionRequestAccepted(long time) {
        boolean ipv6 = this.isIpv6Session();
        ByteBuf buffer = this.allocateBuffer(ipv6 ? IPV6_CONNECTION_REQUEST_ACCEPTED_SIZE : IPV4_CONNECTION_REQUEST_ACCEPTED_SIZE);

        buffer.writeByte(ID_CONNECTION_REQUEST_ACCEPTED);
        NetworkUtils.writeAddress(buffer, this.address);
        buffer.writeShort(0); // System index

        for (int i = 0; i < SYSTEM_ADDRESS_COUNT; i++) {
            InetSocketAddress socketAddress = ipv6 && i == 0 ? LOCAL_IP_ADDRESSES_V6[i] : LOCAL_IP_ADDRESSES_V4[i];
            NetworkUtils.writeAddress(buffer, socketAddress);
        }

        buffer.writeLong(time);
        buffer.writeLong(System.currentTimeMillis());

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.UNRELIABLE);
    }
}
