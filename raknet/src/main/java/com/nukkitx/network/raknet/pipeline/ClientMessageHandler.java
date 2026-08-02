package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakMetrics;
import com.nukkitx.network.raknet.RakNetClient;
import com.nukkitx.network.raknet.RakNetClientSession;
import com.nukkitx.network.raknet.RakNetUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

import static com.nukkitx.network.raknet.RakNetConstants.*;

public class ClientMessageHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-client-message-handler";

    private static final int UNCONNECTED_MAGIC_LENGTH = 16;
    private static final int UNCONNECTED_PONG_HEADER_SIZE = Byte.BYTES + (Long.BYTES * 2) + UNCONNECTED_MAGIC_LENGTH + Short.BYTES;

    private final RakNetClient client;

    public ClientMessageHandler(RakNetClient client) {
        this.client = client;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buffer = packet.content();
        if (!buffer.isReadable() || buffer.readableBytes() > MAXIMUM_MTU_SIZE) {
            return;
        }

        RakMetrics metrics = this.client.getMetrics();
        if (metrics != null) {
            metrics.bytesIn(buffer.readableBytes());
        }

        int packetId = buffer.readUnsignedByte();
        if (packetId == ID_UNCONNECTED_PONG) {
            this.onUnconnectedPong(packet);
            return;
        }

        final RakNetClientSession session = this.client.getSession();
        if (session == null || !session.getAddress().equals(packet.sender())) {
            return;
        }

        ByteBuf buf = buffer.readerIndex(0).retain();
        if (session.getEventLoop().inEventLoop()) {
            session.onDatagram(buf);
        } else {
            try {
                session.getEventLoop().execute(() -> session.onDatagram(buf));
            } catch (RuntimeException exception) {
                buf.release();
                throw exception;
            }
        }
    }

    private void onUnconnectedPong(DatagramPacket packet) {
        if (!this.client.isPingPending(packet.sender())) {
            return;
        }
        ByteBuf content = packet.content();
        if (!content.isReadable(UNCONNECTED_PONG_HEADER_SIZE - Byte.BYTES)) {
            return;
        }

        long pingTime = content.readLong();
        long guid = content.readLong();
        if (!RakNetUtils.verifyUnconnectedMagic(content)) {
            return;
        }

        int userDataLength = content.readUnsignedShort();
        if (userDataLength >= MAXIMUM_OFFLINE_DATA_LENGTH || !content.isReadable(userDataLength)) {
            return;
        }

        byte[] userData = new byte[userDataLength];
        content.readBytes(userData);
        this.client.onUnconnectedPong(new RakNetClient.PongEntry(packet.sender(), pingTime, guid, userData));
    }
}
