package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakMetrics;
import com.nukkitx.network.raknet.RakNet;
import com.nukkitx.network.raknet.RakNetConstants;
import com.nukkitx.network.util.LatencyTrace;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;

public class RakOutboundHandler extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "rak-outbound-handler";
    private final RakNet rakNet;

    public RakOutboundHandler(RakNet rakNet) {
        this.rakNet = rakNet;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof DatagramPacket)) {
            super.write(ctx, msg, promise);
            return;
        }

        RakMetrics metrics = this.rakNet.getMetrics();
        if (metrics != null) {
            ByteBuf buffer = ((DatagramPacket) msg).content();
            metrics.bytesOut(buffer.readableBytes());
        }
        if (LatencyTrace.enabled()) {
            DatagramPacket datagram = (DatagramPacket) msg;
            ByteBuf content = datagram.content();
            int bytes = content.readableBytes();
            int readerIndex = content.readerIndex();
            int flags = bytes == 0 ? 0 : content.getUnsignedByte(readerIndex);
            boolean dataFrame = bytes >= RakNetConstants.RAKNET_DATAGRAM_HEADER_SIZE
                    && (flags & RakNetConstants.FLAG_VALID) != 0
                    && (flags & (RakNetConstants.FLAG_ACK | RakNetConstants.FLAG_NACK)) == 0;
            String key = dataFrame ? Integer.toString(content.getUnsignedMediumLE(readerIndex + 1)) : "";
            String wireKey = LatencyTrace.key(content);
            String address = datagram.recipient().toString();
            long startedNanos = LatencyTrace.clock();
            ChannelPromise tracedPromise = promise.unvoid();
            tracedPromise.addListener(future -> LatencyTrace.record(
                    future.isSuccess() ? "raknet.udp.write_complete" : "raknet.udp.write_failed",
                    address, key, wireKey, bytes, LatencyTrace.clock() - startedNanos));
            super.write(ctx, msg, tracedPromise);
        } else {
            super.write(ctx, msg, promise);
        }
    }
}
