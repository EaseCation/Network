package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetServerSession;
import com.nukkitx.network.raknet.proxy.HAProxyMessage;
import com.nukkitx.network.raknet.proxy.HAProxyProxiedProtocol.AddressFamily;
import com.nukkitx.network.raknet.proxy.HAProxyProtocolException;
import com.nukkitx.network.raknet.proxy.ProxyProtocolDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.handler.codec.ProtocolDetectionState;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;

@ChannelHandler.Sharable
public class ProxyServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(ProxyServerHandler.class);
    public static final String NAME = "rak-proxy-server-handler";

    private final RakNetServer server;

    public ProxyServerHandler(RakNetServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf content = packet.content();
        RakNetServerSession session = this.server.getSession(packet.sender());
        if (session != null) {
            this.server.forwardProxiedDatagram(ctx, packet, session, session.getRealAddress());
            return;
        }

        InetSocketAddress presentAddress = this.server.getProxiedAddress(packet.sender());
        boolean proxyHeader = ProxyProtocolDecoder.detectProtocol(content).state() == ProtocolDetectionState.DETECTED;

        if (proxyHeader) {
            final HAProxyMessage decoded;
            try {
                if ((decoded = ProxyProtocolDecoder.decode(content, ProxyProtocolDecoder.findVersion(content))) == null) {
                    // PROXY header was not present in the packet, ignore.
                    return;
                }
            } catch (HAProxyProtocolException e) {
                log.debug("{} sent malformed PROXY header", packet.sender(), e);
                return;
            }

            AddressFamily addressFamily = decoded.proxiedProtocol().addressFamily();
            if (decoded.sourceAddress() == null || addressFamily != AddressFamily.AF_IPv4 && addressFamily != AddressFamily.AF_IPv6) {
                log.debug("Ignoring PROXY header with an unsupported source address from {}", packet.sender());
                return;
            }

            presentAddress = decoded.sourceInetSocketAddress();
            log.debug("Got PROXY header: (from {}) {}", packet.sender(), presentAddress);
            if (log.isDebugEnabled()) {
                log.debug("PROXY Headers map size: {}", this.server.getProxiedAddressSize());
            }
            if (!this.server.updateProxiedAddress(packet.sender(), presentAddress)) {
                return;
            }
            return;
        }

        if (presentAddress == null) {
            // We haven't received a header from given address before and we couldn't detect a
            // PROXY header, ignore.
            return;
        }

        log.trace("Reusing PROXY header: (from {}) {}", packet.sender(), presentAddress);
        this.server.forwardProxiedDatagram(ctx, packet, null, presentAddress);
    }
}
