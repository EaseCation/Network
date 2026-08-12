package com.nukkitx.network.raknet;

import com.nukkitx.network.NetworkUtils;
import com.nukkitx.network.util.DisconnectReason;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;

import javax.annotation.ParametersAreNonnullByDefault;
import java.net.Inet6Address;
import java.net.InetSocketAddress;

import static com.nukkitx.network.raknet.RakNetConstants.*;

@ParametersAreNonnullByDefault
public class RakNetClientSession extends RakNetSession {
    private final RakNetClient rakNet;
    private int connectionAttempts;
    private long nextConnectionAttempt;
    private int cookie;
    private boolean cookieReceived;

    RakNetClientSession(RakNetClient rakNet, InetSocketAddress address, Channel channel, EventLoop eventLoop, int mtu,
                        int protocolVersion) {
        super(address, channel, eventLoop, mtu, protocolVersion);
        this.rakNet = rakNet;
    }

    @Override
    protected void onPacket(ByteBuf buffer) {
        if (!buffer.isReadable()) {
            return;
        }

        int packetId = buffer.readUnsignedByte();

        boolean handled = false;
        switch (packetId) {
            case ID_OPEN_CONNECTION_REPLY_1:
                handled = this.onOpenConnectionReply1(buffer);
                break;
            case ID_OPEN_CONNECTION_REPLY_2:
                handled = this.onOpenConnectionReply2(buffer);
                break;
            case ID_CONNECTION_REQUEST_ACCEPTED:
                handled = this.onConnectionRequestAccepted(buffer);
                break;
            case ID_CONNECTION_REQUEST_FAILED:
                if (this.getState() == RakNetState.INITIALIZED && this.isValidOfflineFailure(buffer, false, true)) {
                    this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
                }
                break;
            case ID_INCOMPATIBLE_PROTOCOL_VERSION:
                if (this.getState() == RakNetState.UNCONNECTED && this.isValidOfflineFailure(buffer, true, false)) {
                    this.close(DisconnectReason.INCOMPATIBLE_PROTOCOL_VERSION);
                }
                break;
            case ID_ALREADY_CONNECTED:
                if (this.isOfflineHandshakeInProgress() && this.isValidOfflineFailure(buffer, false, false)) {
                    this.close(DisconnectReason.ALREADY_CONNECTED);
                }
                break;
            case ID_NO_FREE_INCOMING_CONNECTIONS:
                if (this.isOfflineHandshakeInProgress() && this.isValidOfflineFailure(buffer, false, false)) {
                    this.close(DisconnectReason.NO_FREE_INCOMING_CONNECTIONS);
                }
                break;
            case ID_IP_RECENTLY_CONNECTED:
                if (this.isOfflineHandshakeInProgress() && this.isValidOfflineFailure(buffer, false, false)) {
                    this.close(DisconnectReason.IP_RECENTLY_CONNECTED);
                }
                break;
            case ID_CONNECTION_BANNED:
                if (this.isOfflineHandshakeInProgress() && this.isValidOfflineFailure(buffer, false, false)) {
                    this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
                }
                break;
        }
        if (handled) {
            this.touch();
        }
    }

    @Override
    protected void tick(long curTime) {
        if (this.getState() == RakNetState.UNCONNECTED) {
            if (this.connectionAttempts >= MAXIMUM_CONNECTION_ATTEMPTS) {
                this.close(DisconnectReason.TIMED_OUT);
            } else {
                if (this.nextConnectionAttempt < curTime) {
                    this.attemptConnection(curTime);
                }
            }
        } else if (this.getState() == RakNetState.INITIALIZING && this.nextConnectionAttempt < curTime) {
            this.sendOpenConnectionRequest2();
            this.nextConnectionAttempt = curTime + 1000;
        }

        super.tick(curTime);
    }

    private void attemptConnection(long curTime) {
        int mtuDiff = (MAXIMUM_MTU_SIZE - MINIMUM_MTU_SIZE) / 9;
        int mtuSize = MAXIMUM_MTU_SIZE - (this.connectionAttempts * mtuDiff);
        if (mtuSize < MINIMUM_MTU_SIZE) {
            mtuSize = MINIMUM_MTU_SIZE;
        }

        this.sendOpenConnectionRequest1(mtuSize);

        this.nextConnectionAttempt = curTime + 1000;
        this.connectionAttempts++;
    }

    @Override
    public RakNet getRakNet() {
        return this.rakNet;
    }

    private boolean onOpenConnectionReply1(ByteBuf buffer) {
        if (this.getState() != RakNetState.UNCONNECTED) {
            return false;
        }
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return false;
        }
        if (!buffer.isReadable(Long.BYTES + 1)) {
            return false;
        }

        long guid = buffer.readLong();
        boolean security = buffer.readBoolean();
        int cookie = 0;
        if (security) {
            if (!buffer.isReadable(Integer.BYTES + Short.BYTES)) {
                return false;
            }
            cookie = buffer.readInt();
        } else if (!buffer.isReadable(Short.BYTES)) {
            return false;
        }
        int mtu = buffer.readUnsignedShort();

        if (buffer.isReadable()) {
            return false;
        }
        if (mtu < MINIMUM_MTU_SIZE || mtu > MAXIMUM_MTU_SIZE) {
            return false;
        }

        this.guid = guid;
        this.cookie = cookie;
        this.cookieReceived = security;
        this.setMtu(mtu);
        this.setState(RakNetState.INITIALIZING);

        this.sendOpenConnectionRequest2();
        this.nextConnectionAttempt = System.currentTimeMillis() + 1000;
        return true;
    }

    private boolean onOpenConnectionReply2(ByteBuf buffer) {
        if (this.getState() != RakNetState.INITIALIZING) {
            return false;
        }
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return false;
        }
        if (!buffer.isReadable(Long.BYTES)) {
            return false;
        }

        int fieldsIndex = buffer.readerIndex();
        buffer.skipBytes(Long.BYTES);
        if (!NetworkUtils.skipAddress(buffer) || !buffer.isReadable(Short.BYTES + 1)) {
            return false;
        }
        buffer.readerIndex(fieldsIndex);

        long guid = buffer.readLong();
        if (this.guid != guid) {
            this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
            return false;
        }
        NetworkUtils.skipAddress(buffer);
        int mtu = buffer.readUnsignedShort();
        boolean security = buffer.readBoolean();
        if (security) {
            this.close(DisconnectReason.CONNECTION_REQUEST_FAILED);
            return false;
        }
        if (buffer.isReadable()) {
            return false;
        }
        if (mtu < MINIMUM_MTU_SIZE || mtu > MAXIMUM_MTU_SIZE) {
            return false;
        }

        this.setMtu(mtu);
        this.initialize();
        this.setState(RakNetState.INITIALIZED);

        this.sendConnectionRequest();
        return true;
    }

    private boolean onConnectionRequestAccepted(ByteBuf buffer) {
        if (this.getState() != RakNetState.INITIALIZED) {
            return false;
        }
        if (!NetworkUtils.skipAddress(buffer) || !buffer.isReadable(Short.BYTES + Long.BYTES * 2)) {
            return false;
        }
        buffer.skipBytes(Short.BYTES);

        long pongTime = buffer.getLong(buffer.writerIndex() - Long.BYTES * 2);
        // Hive sends malformed IPv6 address

        this.sendNewIncomingConnection(pongTime);

        this.setState(RakNetState.CONNECTED);
        return true;
    }

    private boolean isOfflineHandshakeInProgress() {
        RakNetState state = this.getState();
        return state == RakNetState.UNCONNECTED || state == RakNetState.INITIALIZING;
    }

    private boolean isValidOfflineFailure(ByteBuf buffer, boolean includesProtocolVersion, boolean requireGuid) {
        int expectedLength = (includesProtocolVersion ? Byte.BYTES : 0) + 16 + Long.BYTES;
        if (buffer.readableBytes() != expectedLength) {
            return false;
        }
        if (includesProtocolVersion) {
            buffer.skipBytes(Byte.BYTES);
        }
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return false;
        }
        long serverGuid = buffer.readLong();
        return !requireGuid || serverGuid == this.guid;
    }

    private void sendOpenConnectionRequest1(int mtuSize) {
        ByteBuf buffer = this.allocateBuffer(mtuSize);
        buffer.writeByte(ID_OPEN_CONNECTION_REQUEST_1);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeByte(this.protocolVersion);
        buffer.writeZero(mtuSize - 1 - 16 - 1 - (this.address.getAddress() instanceof Inet6Address ? 40 : 20)
                - UDP_HEADER_SIZE); // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header));

        this.sendDirect(buffer);
    }

    private void sendOpenConnectionRequest2() {
        ByteBuf buffer = this.allocateBuffer(this.cookieReceived ? 39 : 34);
        buffer.writeByte(ID_OPEN_CONNECTION_REQUEST_2);
        RakNetUtils.writeUnconnectedMagic(buffer);
        if (this.cookieReceived) {
            buffer.writeInt(this.cookie);
            buffer.writeBoolean(false);
        }
        NetworkUtils.writeAddress(buffer, this.address);
        buffer.writeShort(this.getMtu());
        buffer.writeLong(this.rakNet.guid);

        this.sendDirect(buffer);
    }

    private void sendConnectionRequest() {
        ByteBuf buffer = this.allocateBuffer(18);

        buffer.writeByte(ID_CONNECTION_REQUEST);
        buffer.writeLong(this.rakNet.guid);
        buffer.writeLong(System.currentTimeMillis());
        buffer.writeBoolean(false);

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE_ORDERED);
    }

    private void sendNewIncomingConnection(long pingTime) {
        boolean ipv6 = this.isIpv6Session();
        ByteBuf buffer = this.allocateBuffer(ipv6 ? 626 : 164);

        buffer.writeByte(ID_NEW_INCOMING_CONNECTION);
        NetworkUtils.writeAddress(buffer, address);
        for (InetSocketAddress address : ipv6 ? LOCAL_IP_ADDRESSES_V6 : LOCAL_IP_ADDRESSES_V4) {
            NetworkUtils.writeAddress(buffer, address);
        }
        buffer.writeLong(pingTime);
        buffer.writeLong(System.currentTimeMillis());

        this.send(buffer, RakNetPriority.IMMEDIATE, RakNetReliability.RELIABLE_ORDERED);
    }
}
