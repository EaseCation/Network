package com.nukkitx.network.util;

import io.netty.buffer.ByteBuf;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 默认关闭；只有显式启动后才分配队列、摘要器和文件线程。 */
public final class LatencyTrace {
    @Nullable
    private static volatile Session active;

    private LatencyTrace() {
    }

    /** 仅在初始化期间调用；输出失败只关闭诊断，不影响网络服务启动。 */
    @Nullable
    public static synchronized Session start(Path baseDirectory, String directory, String role, int seconds) {
        InternalLogger logger = InternalLoggerFactory.getInstance(LatencyTrace.class);
        if (active != null) {
            logger.warn("A network latency trace is already active");
            return null;
        }
        if (seconds < 1 || seconds > 3600) {
            logger.warn("Network latency trace duration must be between 1 and 3600 seconds");
            return null;
        }
        try {
            Path outputDirectory = baseDirectory.resolve(directory).toAbsolutePath();
            Files.createDirectories(outputDirectory);
            Path output = Files.createTempFile(outputDirectory, role + "-", ".csv");
            Session session = new Session(output, seconds, logger);
            active = session;
            session.writer.start();
            logger.info("Network latency trace started: {}", output);
            return session;
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            logger.error("Could not start network latency trace", exception);
            return null;
        }
    }

    public static boolean enabled() {
        return active != null;
    }

    public static long clock() {
        return active == null ? 0 : System.nanoTime();
    }

    @Nullable
    public static Stamp stamp(ByteBuf buffer) {
        return active == null ? null : new Stamp(key(buffer), System.nanoTime());
    }

    public record Stamp(String key, long nanoTime) {
    }

    public static String key(byte[] bytes) {
        Session session = active;
        if (session == null) {
            return "";
        }
        MessageDigest digest = session.digest.get();
        digest.reset();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    public static String key(ByteBuf buffer) {
        Session session = active;
        if (session == null) {
            return "";
        }
        MessageDigest digest = session.digest.get();
        digest.reset();
        for (ByteBuffer part : buffer.nioBuffers(buffer.readerIndex(), buffer.readableBytes())) {
            digest.update(part);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static void record(String stage, String connection, String key, String relatedKey, int bytes, long durationNanos) {
        Session session = active;
        if (session != null) {
            session.record(stage, connection, key, relatedKey, bytes, durationNanos);
        }
    }

    public static void elapsed(String stage, String connection, String key, int bytes, long startedNanos) {
        if (active != null && startedNanos != 0) {
            record(stage, connection, key, "", bytes, System.nanoTime() - startedNanos);
        }
    }

    private static synchronized void deactivate(Session session) {
        session.accepting = false;
        if (active == session) {
            active = null;
        }
    }

    public static final class Session implements AutoCloseable {
        private static final int MAX_RECORDS = 1_000_000;
        private final BlockingQueue<Event> events = new ArrayBlockingQueue<>(32768);
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicInteger producers = new AtomicInteger();
        private final AtomicInteger records = new AtomicInteger();
        private final long processId = ProcessHandle.current().pid();
        private final long startedNanos = System.nanoTime();
        private final long durationNanos;
        private final Path output;
        private final BufferedWriter stream;
        private final InternalLogger logger;
        private final Thread writer;
        private final Thread shutdownHook;
        private volatile boolean accepting = true;
        private final ThreadLocal<MessageDigest> digest = ThreadLocal.withInitial(() -> {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        });

        private Session(Path output, int seconds, InternalLogger logger) throws IOException {
            this.output = output;
            this.durationNanos = TimeUnit.SECONDS.toNanos(seconds);
            this.logger = logger;
            this.stream = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
            this.writer = Thread.ofPlatform().daemon().name("NetworkLatencyTrace").unstarted(this::writeEvents);
            this.shutdownHook = Thread.ofPlatform().name("NetworkLatencyTraceShutdown").unstarted(this::close);
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException exception) {
                stream.close();
                throw exception;
            }
        }

        public Path output() {
            return output;
        }

        private void record(String stage, String connection, String key, String relatedKey, int bytes, long elapsedNanos) {
            // 关闭时等待已进入此方法的生产者，避免最后一批入队记录被提前结束的写线程漏掉。
            producers.incrementAndGet();
            try {
                if (!accepting) {
                    return;
                }
                long now = System.nanoTime();
                if (now - startedNanos >= durationNanos || records.incrementAndGet() > MAX_RECORDS) {
                    deactivate(this);
                    return;
                }
                if (!events.offer(new Event(now, System.currentTimeMillis(), stage, connection, key, relatedKey, bytes, elapsedNanos))) {
                    dropped.incrementAndGet();
                }
            } finally {
                producers.decrementAndGet();
            }
        }

        private void writeEvents() {
            try (BufferedWriter outputStream = stream) {
                outputStream.write("process_id,nano_time,epoch_millis,stage,connection,key,related_key,bytes,duration_nanos\n");
                long lastFlush = System.nanoTime();
                long reportedDrops = 0;
                while (accepting || producers.get() != 0 || !events.isEmpty()) {
                    Event event = events.poll(250, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        writeEvent(outputStream, event);
                    }
                    long now = System.nanoTime();
                    if (now - startedNanos >= durationNanos) {
                        deactivate(this);
                    }
                    if (now - lastFlush >= TimeUnit.SECONDS.toNanos(1)) {
                        reportedDrops = reportDrops(outputStream, reportedDrops);
                        outputStream.flush();
                        lastFlush = now;
                    }
                }
                reportDrops(outputStream, reportedDrops);
                outputStream.flush();
            } catch (IOException | RuntimeException exception) {
                logger.error("Could not write network latency trace", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logger.warn("Network latency trace writer was interrupted", exception);
            } finally {
                deactivate(this);
                events.clear();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM 已进入关闭流程，关闭钩子由运行时回收。
                }
            }
        }

        private long reportDrops(BufferedWriter outputStream, long reported) throws IOException {
            long count = dropped.get();
            if (count != reported) {
                writeEvent(outputStream, new Event(System.nanoTime(), System.currentTimeMillis(), "trace.dropped", "", "", "", 0, count));
            }
            return count;
        }

        private void writeEvent(BufferedWriter outputStream, Event event) throws IOException {
            outputStream.write(processId + "," + event.nanoTime + "," + event.epochMillis + ","
                    + quote(event.stage) + "," + quote(event.connection) + "," + quote(event.key) + ","
                    + quote(event.relatedKey) + "," + event.bytes + "," + event.durationNanos + "\n");
        }

        private static String quote(String value) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        @Override
        public void close() {
            deactivate(this);
            try {
                writer.join(2000);
                if (writer.isAlive()) {
                    logger.warn("Network latency trace writer did not finish within the shutdown timeout: {}", output);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record Event(long nanoTime, long epochMillis, String stage, String connection, String key,
                         String relatedKey, int bytes, long durationNanos) {
    }
}
