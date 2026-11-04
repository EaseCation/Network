package com.nukkitx.network.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class LatencyTraceTest {
    @TempDir
    Path directory;

    @Test
    void disabledDoesNotReadBuffersOrCreateOutput() throws Exception {
        assertFalse(LatencyTrace.enabled());
        assertEquals(0, LatencyTrace.clock());
        ByteBuf released = Unpooled.buffer();
        released.release();
        assertEquals("", LatencyTrace.key(released));
        assertNull(LatencyTrace.stamp(released));
        LatencyTrace.record("disabled", "", "", "", 0, 0);
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void closeDrainsConcurrentProducersAndOnlyWritesFingerprints() throws Exception {
        LatencyTrace.Session session = LatencyTrace.start(directory, "trace", "test", 30);
        assertNotNull(session);
        String payload = "private-packet-body";
        ByteBuf bytes = Unpooled.copiedBuffer(payload, StandardCharsets.UTF_8);
        String key;
        try {
            key = LatencyTrace.key(bytes);
            assertEquals(0, bytes.readerIndex());
            assertEquals(payload, bytes.toString(StandardCharsets.UTF_8));
        } finally {
            bytes.release();
        }
        List<Thread> producers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try (session) {
            for (int index = 0; index < 4; index++) {
                Thread producer = Thread.ofPlatform().start(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int row = 0; row < 1000; row++) {
                        LatencyTrace.record("test", "connection", key, "", 19, row);
                    }
                });
                producers.add(producer);
            }
            start.countDown();
            for (Thread producer : producers) {
                producer.join(5000);
                assertFalse(producer.isAlive());
            }
        }
        assertFalse(LatencyTrace.enabled());
        List<String> rows = Files.readAllLines(session.output());
        assertEquals(4001, rows.size());
        assertFalse(String.join("\n", rows).contains(payload));
    }

    @Test
    void invalidOutputFailsClosed() throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "occupied");
        assertNull(LatencyTrace.start(file, "child", "test", 30));
        assertFalse(LatencyTrace.enabled());
        assertNull(LatencyTrace.start(directory, "trace", "test", 0));
    }

    @Test
    void expiresAndOldHandleCannotCloseNewSession() throws Exception {
        LatencyTrace.Session first = LatencyTrace.start(directory, "trace", "test", 1);
        assertNotNull(first);
        try (first) {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (LatencyTrace.enabled() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(LatencyTrace.enabled());
            LatencyTrace.Session second = LatencyTrace.start(directory, "trace", "test", 30);
            assertNotNull(second);
            try (second) {
                first.close();
                assertTrue(LatencyTrace.enabled());
                assertNotEquals(first.output(), second.output());
                LatencyTrace.record("second", "", "", "", 0, 0);
            }
            assertTrue(Files.readString(second.output()).contains("second"));
        }
    }
}
