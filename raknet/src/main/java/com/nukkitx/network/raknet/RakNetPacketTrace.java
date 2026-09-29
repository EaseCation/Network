package com.nukkitx.network.raknet;

import com.nukkitx.network.util.LatencyTrace;
import io.netty.buffer.ByteBuf;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;

/** 将诊断字段留在可选子类中，关闭时保持原包的内存布局。 */
public final class RakNetPacketTrace {
    private RakNetPacketTrace() {
    }

    public static EncapsulatedPacket inbound() {
        return LatencyTrace.enabled() ? new TracedPacket(null, System.nanoTime()) : new EncapsulatedPacket();
    }

    static EncapsulatedPacket outbound(@Nullable LatencyTrace.Stamp stamp) {
        return stamp == null ? new EncapsulatedPacket() : new TracedPacket(stamp, 0);
    }

    public static void inheritReceiveTime(EncapsulatedPacket result, EncapsulatedPacket[] parts) {
        if (result instanceof TracedPacket traced) {
            for (EncapsulatedPacket part : parts) {
                if (part instanceof TracedPacket source && source.receivedNanos != 0
                        && (traced.receivedNanos == 0 || source.receivedNanos < traced.receivedNanos)) {
                    traced.receivedNanos = source.receivedNanos;
                }
            }
        }
    }

    static void received(EncapsulatedPacket packet, InetSocketAddress address, ByteBuf buffer) {
        if (packet instanceof TracedPacket traced && LatencyTrace.enabled()) {
            LatencyTrace.elapsed("raknet.receive_wait", address.toString(), LatencyTrace.key(buffer),
                    buffer.readableBytes(), traced.receivedNanos);
        }
    }

    static void dequeued(EncapsulatedPacket packet, InetSocketAddress address, int size) {
        if (packet instanceof TracedPacket traced && traced.sent != null && LatencyTrace.enabled()) {
            LatencyTrace.record("raknet.send_queue", address.toString(), traced.sent.key(),
                    Integer.toString(packet.partIndex), size, System.nanoTime() - traced.sent.nanoTime());
        }
    }

    static void datagram(EncapsulatedPacket packet, InetSocketAddress address, String sequence, boolean retransmit) {
        if (packet instanceof TracedPacket traced && traced.sent != null) {
            LatencyTrace.record(retransmit ? "raknet.datagram.retransmit" : "raknet.datagram.member",
                    address.toString(), traced.sent.key(), sequence, packet.getSize(), -1);
        }
    }

    private static final class TracedPacket extends EncapsulatedPacket {
        @Nullable
        private final LatencyTrace.Stamp sent;
        private long receivedNanos;

        private TracedPacket(@Nullable LatencyTrace.Stamp sent, long receivedNanos) {
            this.sent = sent;
            this.receivedNanos = receivedNanos;
        }

        @Override
        public EncapsulatedPacket fromSplit(ByteBuf reassembled) {
            TracedPacket packet = new TracedPacket(null, receivedNanos);
            packet.reliability = reliability;
            packet.reliabilityIndex = reliabilityIndex;
            packet.sequenceIndex = sequenceIndex;
            packet.orderingIndex = orderingIndex;
            packet.orderingChannel = orderingChannel;
            packet.buffer = reassembled;
            return packet;
        }
    }
}
