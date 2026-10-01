package com.nukkitx.network.raknet;

import com.nukkitx.network.util.LatencyTrace;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RakNetPacketTraceTest {
    @TempDir
    Path directory;

    @Test
    void disabledKeepsOriginalPacketType() {
        assertFalse(LatencyTrace.enabled());
        assertEquals(EncapsulatedPacket.class, RakNetPacketTrace.inbound().getClass());
        assertEquals(EncapsulatedPacket.class, RakNetPacketTrace.outbound(null).getClass());
    }

    @Test
    void traceDoesNotChangeWireFormatOrReassembly() {
        byte[] expected = ByteBufUtil.decodeHexDump("60002005000007000000fe010203");
        EncapsulatedPacket baseline = RakNetPacketTrace.inbound();
        try {
            assertArrayEquals(expected, encode(baseline));
        } finally {
            baseline.release();
        }
        LatencyTrace.Session session = LatencyTrace.start(directory, "trace", "test", 30);
        assertNotNull(session);
        try (session) {
            EncapsulatedPacket original = RakNetPacketTrace.inbound();
            assertArrayEquals(expected, encode(original));
            ByteBuf joined = Unpooled.wrappedBuffer(new byte[]{(byte) 0xfe, 1, 2, 3});
            EncapsulatedPacket reassembled = original.fromSplit(joined);
            RakNetPacketTrace.inheritReceiveTime(reassembled, new EncapsulatedPacket[]{original});
            ByteBuf output = Unpooled.buffer();
            try {
                reassembled.encode(output);
                assertArrayEquals(expected, ByteBufUtil.getBytes(output));
            } finally {
                output.release();
                reassembled.release();
                original.release();
            }
        }
    }

    private static byte[] encode(EncapsulatedPacket packet) {
        packet.reliability = RakNetReliability.RELIABLE_ORDERED;
        packet.reliabilityIndex = 5;
        packet.orderingIndex = 7;
        packet.buffer = Unpooled.wrappedBuffer(new byte[]{(byte) 0xfe, 1, 2, 3});
        ByteBuf output = Unpooled.buffer();
        try {
            packet.encode(output);
            return ByteBufUtil.getBytes(output);
        } finally {
            output.release();
        }
    }
}
