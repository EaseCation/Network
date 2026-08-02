package com.nukkitx.network.raknet;

import com.nukkitx.network.raknet.pipeline.*;
import com.nukkitx.network.raknet.util.RoundRobinIterator;
import com.nukkitx.network.util.Bootstraps;
import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.network.util.EventLoops;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import javax.annotation.Nonnegative;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static com.nukkitx.network.raknet.RakNetConstants.*;

@ParametersAreNonnullByDefault
public class RakNetServer extends RakNet {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetServer.class);
    private static final int MAXIMUM_PROXIED_ADDRESS_COUNT = 65536;
    private static final int SESSION_CREATION_LOCK_COUNT = 256;

    private final ConcurrentMap<InetAddress, Long> blockAddresses = new ConcurrentHashMap<>();
    final ConcurrentMap<InetSocketAddress, RakNetServerSession> sessionsByAddress = new ConcurrentHashMap<>();
    final ExpiringMap<InetSocketAddress, InetSocketAddress> proxiedAddresses;
    private final Object sessionAdmissionLock = new Object();
    private final Object sessionQuotaLock = new Object();
    private final Object[] sessionCreationLocks = new Object[SESSION_CREATION_LOCK_COUNT];
    private final Object2IntMap<InetAddress> sessionCountsByAddress = new Object2IntOpenHashMap<>();

    private final InetSocketAddress bindAddress;
    private final int bindThreads;
    private final boolean useProxyProtocol;
    private int maxConnections = 1024;
    private int maxConnectionsPerIp = 64;
    private int reservedSessionCount;
    private int pendingSessionCount;
    private int maxPendingSessions = 1024;

    private final Set<Channel> channels = ConcurrentHashMap.newKeySet();
    private final Iterator<Channel> channelIterator = new RoundRobinIterator<>(channels);

    private final ServerChannelInitializer initializer = new ServerChannelInitializer();
    private final ServerMessageHandler messageHandler = new ServerMessageHandler(this);
    private final ProxyServerHandler proxyServerHandler;
    private final ServerRateLimiter rateLimiter = new ServerRateLimiter(this);
    private final ServerDatagramHandler serverDatagramHandler = new ServerDatagramHandler(this);
    private final RakExceptionHandler exceptionHandler = new RakExceptionHandler(this);

    private RakNetServerListener listener = null;
    private int packetLimit = DEFAULT_PACKET_LIMIT;

    public RakNetServer(InetSocketAddress bindAddress) {
        this(bindAddress, 1);
    }

    public RakNetServer(InetSocketAddress bindAddress, int bindThreads) {
        this(bindAddress, bindThreads, EventLoops.commonGroup());
    }

    public RakNetServer(InetSocketAddress bindAddress, int bindThreads, EventLoopGroup eventLoopGroup) {
        this(bindAddress, bindThreads, eventLoopGroup, false);
    }

    public RakNetServer(InetSocketAddress bindAddress, int bindThreads, EventLoopGroup eventLoopGroup, boolean useProxyProtocol) {
        super(eventLoopGroup);
        this.bindThreads = bindThreads;
        this.bindAddress = bindAddress;
        this.useProxyProtocol = useProxyProtocol;
        for (int i = 0; i < this.sessionCreationLocks.length; i++) {
            this.sessionCreationLocks[i] = new Object();
        }
        this.proxiedAddresses = ExpiringMap.builder()
                .maxSize(MAXIMUM_PROXIED_ADDRESS_COUNT)
                .expiration(30 + 1, TimeUnit.MINUTES)
                .expirationPolicy(ExpirationPolicy.ACCESSED)
                .build();
        this.proxyServerHandler = useProxyProtocol ? new ProxyServerHandler(this) : null;
    }

    @Override
    protected CompletableFuture<Void> bindInternal() {
        int bindThreads = Bootstraps.isReusePortAvailable() ? this.bindThreads : 1;
        ChannelFuture[] channelFutures = new ChannelFuture[bindThreads];

        for (int i = 0; i < bindThreads; i++) {
            channelFutures[i] = this.bootstrap.handler(this.initializer).bind(this.bindAddress);
        }
        return Bootstraps.allOf(channelFutures);
    }

    public void send(InetSocketAddress address, ByteBuf buffer) {
        this.channelIterator.next().writeAndFlush(new DatagramPacket(buffer, address));
    }

    @Override
    public void close(boolean force) {
        super.close(force);
        List<RakNetServerSession> sessions;
        synchronized (this.sessionAdmissionLock) {
            sessions = new ArrayList<>(this.sessionsByAddress.values());
        }
        for (RakNetServerSession session : sessions) {
            session.disconnect(DisconnectReason.SHUTTING_DOWN);
        }
        for (Channel channel : this.channels) {
            channel.close().syncUninterruptibly();
        }
    }

    @Override
    protected void onTick() {
        final long curTime = System.currentTimeMillis();
        this.rateLimiter.onTick(curTime);
        Iterator<Long> blockedAddresses = this.blockAddresses.values().iterator();
        long timeout;
        while (blockedAddresses.hasNext()) {
            timeout = blockedAddresses.next();
            if (timeout > 0 && timeout < curTime) {
                blockedAddresses.remove();
            }
        }
    }

    public void onOpenConnectionRequest1(ChannelHandlerContext ctx, DatagramPacket packet) {
        if (this.isClosed() || !packet.content().isReadable(17)) {
            return;
        }
        // We want to do as many checks as possible before creating a session so memory is not wasted.
        ByteBuf buffer = packet.content();
        if (!RakNetUtils.verifyUnconnectedMagic(buffer)) {
            return;
        }
        int protocolVersion = buffer.readUnsignedByte();
        int mtu = buffer.readableBytes() + 1 + 16 + 1 + (packet.sender().getAddress() instanceof Inet6Address ? IPV6_HEADER_SIZE : IPV4_HEADER_SIZE)
                + UDP_HEADER_SIZE; // 1 (Packet ID), 16 (Magic), 1 (Protocol Version), 20/40 (IP Header)
        if (mtu < MINIMUM_MTU_SIZE || mtu > MAXIMUM_MTU_SIZE) {
            return;
        }

        synchronized (this.getSessionCreationLock(packet.sender())) {
            if (this.isClosed()) {
                return;
            }
            RakNetServerSession session = this.sessionsByAddress.get(packet.sender());
            InetSocketAddress proxiedAddress = this.useProxyProtocol ? (session == null ? this.proxiedAddresses.get(packet.sender()) : session.proxiedAddress) : null;
            if (this.useProxyProtocol && session == null && proxiedAddress == null) {
                return;
            }
            InetSocketAddress clientAddress = proxiedAddress == null ? packet.sender() : proxiedAddress;
            if (session != null && session.getState() == RakNetState.CONNECTED) {
                this.sendAlreadyConnected(ctx, packet.sender());
            /*} else if (this.protocolVersion >= 0 && this.protocolVersion != protocolVersion) { // multi-version compatibility
                this.sendIncompatibleProtocolVersion(ctx, packet.sender());
            */} else if (session == null) {
                InetAddress quotaAddress = clientAddress.getAddress();
                if (quotaAddress == null || !this.tryAcquireSession(quotaAddress)) {
                    this.sendNoFreeIncomingConnections(ctx, packet.sender());
                    return;
                }

                RakNetServerSession newSession = null;
                boolean reservationsRegistered = false;
                boolean installed = false;
                RakNetServerListener listener = this.listener;
                try {
                    if (listener != null && !listener.onConnectionRequest(packet.sender(), clientAddress)) {
                        this.sendConnectionBanned(ctx, packet.sender());
                        return;
                    }
                    newSession = new RakNetServerSession(this, packet.sender(), ctx.channel(), ctx.channel().eventLoop().next(), mtu, protocolVersion);
                    newSession.registerPendingSession();
                    newSession.registerSessionQuota(quotaAddress);
                    newSession.proxiedAddress = proxiedAddress;
                    reservationsRegistered = true;

                    boolean sessionInstalled;
                    synchronized (this.sessionAdmissionLock) {
                        sessionInstalled = !this.isClosed() && this.sessionsByAddress.putIfAbsent(packet.sender(), newSession) == null;
                    }
                    if (sessionInstalled) {
                        installed = true;
                        try {
                            newSession.setState(RakNetState.INITIALIZING);
                            newSession.sendOpenConnectionReply1();
                            if (this.isClosed()) {
                                newSession.disconnect(DisconnectReason.SHUTTING_DOWN);
                            }
                        } catch (RuntimeException | Error throwable) {
                            newSession.close(DisconnectReason.DISCONNECTED);
                            throw throwable;
                        }
                    } else {
                        RakNetServerSession existingSession = this.sessionsByAddress.get(packet.sender());
                        if (existingSession != null) {
                            existingSession.sendOpenConnectionReply1();
                        }
                    }
                } finally {
                    if (!installed) {
                        if (reservationsRegistered) {
                            newSession.releaseSessionReservations();
                        } else {
                            this.releaseSession(quotaAddress, true);
                        }
                    }
                }
            } else {
                session.sendOpenConnectionReply1(); // probably a packet loss occurred, send the reply again
            }
        }
    }

    Object getSessionCreationLock(InetSocketAddress address) {
        int hash = address.hashCode();
        return this.sessionCreationLocks[(hash ^ hash >>> 16) & (this.sessionCreationLocks.length - 1)];
    }

    public void block(InetAddress address) {
        Objects.requireNonNull(address, "address");
        this.blockAddresses.put(address, -1L);
    }

    public void block(InetAddress address, long timeout, TimeUnit timeUnit) {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(address, "timeUnit");
        this.blockAddresses.put(address, System.currentTimeMillis() + timeUnit.toMillis(timeout));
    }

    public boolean unblock(InetAddress address) {
        Objects.requireNonNull(address, "address");
        return this.blockAddresses.remove(address) != null;
    }

    public boolean isBlocked(InetAddress address) {
        return this.blockAddresses.containsKey(address);
    }

    public void addProxiedAddress(InetSocketAddress address, InetSocketAddress presentAddress) {
        synchronized (this.getSessionCreationLock(address)) {
            this.proxiedAddresses.put(address, presentAddress);
        }
    }

    public boolean updateProxiedAddress(InetSocketAddress address, InetSocketAddress presentAddress) {
        synchronized (this.getSessionCreationLock(address)) {
            if (this.sessionsByAddress.containsKey(address)) {
                return false;
            }
            this.proxiedAddresses.put(address, presentAddress);
            return true;
        }
    }

    public void forwardProxiedDatagram(ChannelHandlerContext ctx, DatagramPacket packet, @Nullable RakNetServerSession expectedSession, InetSocketAddress expectedAddress) {
        synchronized (this.getSessionCreationLock(packet.sender())) {
            RakNetServerSession session = this.sessionsByAddress.get(packet.sender());
            if (expectedSession == null) {
                if (session != null || !expectedAddress.equals(this.proxiedAddresses.get(packet.sender()))) {
                    return;
                }
            } else if (session != expectedSession) {
                return;
            }

            InetAddress address = expectedAddress.getAddress();
            if (address == null || !this.isBlocked(address)) {
                ctx.fireChannelRead(packet.retain());
            }
        }
    }

    public InetSocketAddress getProxiedAddress(InetSocketAddress address) {
        return this.proxiedAddresses.get(address);
    }

    public boolean removeProxiedAddress(InetSocketAddress address, InetSocketAddress expectedAddress) {
        return this.proxiedAddresses.remove(address, expectedAddress);
    }

    public int getProxiedAddressSize() {
        return this.proxiedAddresses.size();
    }

    public int getSessionCount() {
        return this.sessionsByAddress.size();
    }

    @Nullable
    public RakNetServerSession getSession(InetSocketAddress address) {
        return this.sessionsByAddress.get(address);
    }

    @Nonnegative
    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(@Nonnegative int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxConnectionsPerIp() {
        return this.maxConnectionsPerIp;
    }

    public void setMaxConnectionsPerIp(int maxConnectionsPerIp) {
        if (maxConnectionsPerIp <= 0) {
            throw new IllegalArgumentException("maxConnectionsPerIp must be positive");
        }
        this.maxConnectionsPerIp = maxConnectionsPerIp;
    }

    public int getSessionCount(InetAddress address) {
        Objects.requireNonNull(address, "address");
        synchronized (this.sessionQuotaLock) {
            return this.sessionCountsByAddress.getOrDefault(address, 0);
        }
    }

    public int getMaxPendingSessions() {
        return this.maxPendingSessions;
    }

    public void setMaxPendingSessions(int maxPendingSessions) {
        if (maxPendingSessions <= 0) {
            throw new IllegalArgumentException("maxPendingSessions must be positive");
        }
        this.maxPendingSessions = maxPendingSessions;
    }

    public int getPendingSessionCount() {
        synchronized (this.sessionQuotaLock) {
            return this.pendingSessionCount;
        }
    }

    private boolean tryAcquireSession(InetAddress address) {
        int maxConnections = this.maxConnections;
        synchronized (this.sessionQuotaLock) {
            int addressCount;
            if (maxConnections > 0 && this.reservedSessionCount >= maxConnections ||
                    (addressCount = this.sessionCountsByAddress.getOrDefault(address, 0)) >= this.maxConnectionsPerIp ||
                    this.pendingSessionCount >= this.maxPendingSessions) {
                return false;
            }
            this.reservedSessionCount++;
            this.pendingSessionCount++;
            this.sessionCountsByAddress.put(address, addressCount + 1);
            return true;
        }
    }

    void releaseSession(InetAddress address, boolean pending) {
        synchronized (this.sessionQuotaLock) {
            int addressCount = this.sessionCountsByAddress.getOrDefault(address, 0);
            if (this.reservedSessionCount <= 0 || addressCount <= 0 || pending && this.pendingSessionCount <= 0) {
                throw new IllegalStateException("Session count became negative");
            }
            this.reservedSessionCount--;
            if (pending) {
                this.pendingSessionCount--;
            }
            if (addressCount == 1) {
                this.sessionCountsByAddress.removeInt(address);
            } else {
                this.sessionCountsByAddress.put(address, addressCount - 1);
            }
        }
    }

    void releasePendingSession() {
        synchronized (this.sessionQuotaLock) {
            if (this.pendingSessionCount <= 0) {
                throw new IllegalStateException("Pending session count became negative");
            }
            this.pendingSessionCount--;
        }
    }

    void notifySessionCreation(RakNetServerSession session) {
        RakNetServerListener listener = this.listener;
        if (listener != null) {
            listener.onSessionCreation(session);
        }
    }

    public int getPacketLimit() {
        return this.packetLimit;
    }

    public void setPacketLimit(int packetLimit) {
        if (packetLimit <= 0) {
            throw new IllegalArgumentException("packetLimit must be positive");
        }
        this.packetLimit = packetLimit;
    }

    @Override
    public InetSocketAddress getBindAddress() {
        return this.bindAddress;
    }

    public RakNetServerListener getListener() {
        return listener;
    }

    public void setListener(RakNetServerListener listener) {
        this.listener = listener;
    }

    public boolean useProxyProtocol() {
        return this.useProxyProtocol;
    }

    /*
     * Packet Dispatchers
     */

    private void sendAlreadyConnected(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_ALREADY_CONNECTED);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    private void sendConnectionBanned(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_CONNECTION_BANNED);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    private void sendIncompatibleProtocolVersion(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(26, 26);
        buffer.writeByte(ID_INCOMPATIBLE_PROTOCOL_VERSION);
        buffer.writeByte(this.protocolVersion);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    private void sendNoFreeIncomingConnections(ChannelHandlerContext ctx, InetSocketAddress recipient) {
        ByteBuf buffer = ctx.alloc().ioBuffer(25, 25);
        buffer.writeByte(ID_NO_FREE_INCOMING_CONNECTIONS);
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeLong(this.guid);
        ctx.writeAndFlush(new DatagramPacket(buffer, recipient));
    }

    @ChannelHandler.Sharable
    private class ServerChannelInitializer extends ChannelInitializer<Channel> {

        @Override
        protected void initChannel(Channel channel) throws Exception {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast(ServerRateLimiter.NAME, RakNetServer.this.rateLimiter);
            if (RakNetServer.this.useProxyProtocol()) {
                pipeline.addLast(ProxyServerHandler.NAME, RakNetServer.this.proxyServerHandler);
            }
            pipeline.addLast(RakOutboundHandler.NAME, new RakOutboundHandler(RakNetServer.this));
            pipeline.addLast(ServerMessageHandler.NAME, RakNetServer.this.messageHandler);
            pipeline.addLast(ServerDatagramHandler.NAME, RakNetServer.this.serverDatagramHandler);
            pipeline.addLast(RakExceptionHandler.NAME, RakNetServer.this.exceptionHandler);
            RakNetServer.this.channels.add(channel);
        }
    }
}
