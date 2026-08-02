package com.nukkitx.network.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

@Data
@Setter(AccessLevel.NONE)
public class EncapsulatedPacket implements ReferenceCounted {
    RakNetReliability reliability;
    RakNetPriority priority;
    int reliabilityIndex;
    int sequenceIndex;
    int orderingIndex;
    short orderingChannel;
    boolean split;
    int partCount;
    int partId;
    int partIndex;
    ByteBuf buffer;
    boolean needsBAS;

    public void encode(ByteBuf buf) {
        int flags = reliability.getId() << 5;
        if (split) {
            flags |= RakNetConstants.FLAG_PACKET_PAIR;
        }
        if (needsBAS){
            flags |= RakNetConstants.FLAG_NEEDS_B_AND_AS;
        }
        buf.writeByte(flags); // flags
        buf.writeShort(buffer.readableBytes() << 3); // size

        if (reliability.isReliable()) {
            buf.writeMediumLE(reliabilityIndex);
        }

        if (reliability.isSequenced()) {
            buf.writeMediumLE(sequenceIndex);
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            buf.writeMediumLE(orderingIndex);
            buf.writeByte(orderingChannel);
        }

        if (split) {
            buf.writeInt(partCount);
            buf.writeShort(partId);
            buf.writeInt(partIndex);
        }

        buf.writeBytes(this.buffer, this.buffer.readerIndex(), this.buffer.readableBytes());
        // If we need to resend, we don't want the buffer's reader index changing.
    }

    public boolean decode(ByteBuf buf) {
        int readerIndex = buf.readerIndex();
        int readableBytes = buf.readableBytes();
        if (readableBytes < 3) {
            return false;
        }

        int flags = buf.getUnsignedByte(readerIndex);
        RakNetReliability reliability = RakNetReliability.fromId((flags & 0b11100000) >> 5);
        if (reliability == null) {
            return false;
        }

        boolean split = (flags & RakNetConstants.FLAG_PACKET_PAIR) != 0;
        int headerSize = 3 + reliability.getSize() + (split ? 10 : 0);
        if (readableBytes < headerSize) {
            return false;
        }

        int bitLength = buf.getUnsignedShort(readerIndex + 1);
        int size = (bitLength + 7) >>> 3;
        if (size == 0 || readableBytes - headerSize < size) {
            return false;
        }

        buf.skipBytes(3);
        int reliabilityIndex = 0;
        int sequenceIndex = 0;
        int orderingIndex = 0;
        short orderingChannel = 0;
        int partCount = 0;
        int partId = 0;
        int partIndex = 0;

        if (reliability.isReliable()) {
            reliabilityIndex = buf.readUnsignedMediumLE();
        }

        if (reliability.isSequenced()) {
            sequenceIndex = buf.readUnsignedMediumLE();
        }

        if (reliability.isOrdered() || reliability.isSequenced()) {
            orderingIndex = buf.readUnsignedMediumLE();
            orderingChannel = buf.readUnsignedByte();
        }

        if (split) {
            partCount = buf.readInt();
            partId = buf.readUnsignedShort();
            partIndex = buf.readInt();
        }

        ByteBuf buffer = buf.readSlice(size);
        this.reliability = reliability;
        this.reliabilityIndex = reliabilityIndex;
        this.sequenceIndex = sequenceIndex;
        this.orderingIndex = orderingIndex;
        this.orderingChannel = orderingChannel;
        this.split = split;
        this.partCount = partCount;
        this.partId = partId;
        this.partIndex = partIndex;
        this.buffer = buffer;
        this.needsBAS = (flags & RakNetConstants.FLAG_NEEDS_B_AND_AS) != 0;
        return true;
    }

    public int getSize() {
        // Include back of the envelope calculation
        return 3 + this.reliability.getSize() + (this.split ? 10 : 0) + this.buffer.readableBytes();
    }

    public EncapsulatedPacket fromSplit(ByteBuf reassembled) {
        EncapsulatedPacket packet = new EncapsulatedPacket();
        packet.reliability = this.reliability;
        packet.reliabilityIndex = this.reliabilityIndex;
        packet.sequenceIndex = this.sequenceIndex;
        packet.orderingIndex = this.orderingIndex;
        packet.orderingChannel = this.orderingChannel;
        packet.buffer = reassembled;
        return packet;
    }

    @Override
    public int refCnt() {
        return buffer.refCnt();
    }

    @Override
    public EncapsulatedPacket retain() {
        this.buffer.retain();
        return this;
    }

    @Override
    public EncapsulatedPacket retain(int i) {
        this.buffer.retain(i);
        return this;
    }

    @Override
    public EncapsulatedPacket touch() {
        this.buffer.touch();
        return this;
    }

    @Override
    public EncapsulatedPacket touch(Object o) {
        this.buffer.touch(o);
        return this;
    }

    @Override
    public boolean release() {
        return buffer.release();
    }

    @Override
    public boolean release(int i) {
        return buffer.release(i);
    }
}
