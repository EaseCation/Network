package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakNetServer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nukkitx.network.raknet.RakNetConstants.*;

@ChannelHandler.Sharable
public final class ServerRateLimiter extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-server-rate-limiter";
    private static final InternalLogger log = InternalLoggerFactory.getInstance(ServerRateLimiter.class);
    private static final long BLOCKED_ENDPOINT_CLEANUP_INTERVAL_MS = 1000;

    private final RakNetServer server;
    private final ConcurrentMap<InetSocketAddress, AtomicInteger> endpointCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<InetSocketAddress, Long> blockedEndpoints = new ConcurrentHashMap<>();
    private boolean blockedEndpointCleanupInitialized;
    private long lastBlockedEndpointCleanupTime;

    public ServerRateLimiter(RakNetServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public void onTick(long currentTime) {
        this.endpointCounters.clear();

        if (!this.shouldCleanupBlockedEndpoints(currentTime)) {
            return;
        }
        for (Map.Entry<InetSocketAddress, Long> entry : this.blockedEndpoints.entrySet()) {
            if (entry.getValue() <= currentTime) {
                this.blockedEndpoints.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean shouldCleanupBlockedEndpoints(long currentTime) {
        if (!this.blockedEndpointCleanupInitialized) {
            this.blockedEndpointCleanupInitialized = true;
            this.lastBlockedEndpointCleanupTime = currentTime;
            return true;
        }

        long previousTime = this.lastBlockedEndpointCleanupTime;
        if (currentTime < previousTime) {
            this.lastBlockedEndpointCleanupTime = currentTime;
            return true;
        }

        if (Long.compareUnsigned(currentTime - previousTime, BLOCKED_ENDPOINT_CLEANUP_INTERVAL_MS) < 0) {
            return false;
        }
        this.lastBlockedEndpointCleanupTime = currentTime;
        return true;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        if (!packet.content().isReadable() || packet.content().readableBytes() > MAXIMUM_MTU_SIZE) {
            return;
        }

        InetSocketAddress sender = packet.sender();
        if (this.blockedEndpoints.containsKey(sender)) {
            return;
        }

        AtomicInteger counter = this.endpointCounters.computeIfAbsent(sender, ignored -> new AtomicInteger());
        if (counter.incrementAndGet() > this.server.getPacketLimit()) {
            long blockedUntil = System.currentTimeMillis() + RATE_LIMIT_BLOCK_DURATION_MS;
            if (this.blockedEndpoints.putIfAbsent(sender, blockedUntil) == null) {
                log.debug("[{}] Blocked because packet limit was reached", sender);
            }
            return;
        }

        ctx.fireChannelRead(packet.retain());
    }
}
