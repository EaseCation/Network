package com.nukkitx.network.raknet;

import com.nukkitx.network.raknet.pipeline.RakOutboundHandler;
import com.nukkitx.network.util.LatencyTrace;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class RakOutboundTraceTest {
    @TempDir
    Path directory;

    @Test
    void tracePreservesVoidPromiseFailurePropagation() {
        LatencyTrace.Session session = LatencyTrace.start(directory, "trace", "test", 30);
        assertNotNull(session);
        RuntimeException failure = new IllegalStateException("Test write failure");
        try (session) {
            EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    ReferenceCountUtil.release(message);
                    promise.setFailure(failure);
                }
            }, new RakOutboundHandler(mock(RakNet.class)));
            try {
                channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(new byte[]{(byte) 0x80, 0, 0, 0}),
                        new InetSocketAddress("127.0.0.1", 19132)), channel.voidPromise());
                RuntimeException actual = assertThrows(RuntimeException.class, channel::checkException);
                assertSame(failure, actual);
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }
}
