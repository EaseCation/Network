package com.nukkitx.network.raknet;

import com.nukkitx.network.raknet.util.IntRange;
import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

import java.util.Queue;

@UtilityClass
public class RakNetUtils {
    private static final long SEQUENCE_INDEX_MASK = 0xFFFFFFL;
    private static final int HALF_SEQUENCE_INDEX_COUNT = 0x800000;

    public static int getSequenceIndexDelta(long sequenceIndex, long expectedIndex) {
        return (int) ((sequenceIndex - expectedIndex) & SEQUENCE_INDEX_MASK);
    }

    public static boolean isSequenceIndexAhead(long sequenceIndex, long expectedIndex) {
        int delta = getSequenceIndexDelta(sequenceIndex, expectedIndex);
        return delta != 0 && delta < HALF_SEQUENCE_INDEX_COUNT;
    }

    public static int writeIntRanges(ByteBuf buffer, Queue<IntRange> ackQueue, int mtu) {
        int lengthIndex = buffer.writerIndex();
        buffer.writeZero(2);
        mtu -= 2;

        int count = 0;
        long written = 0;
        IntRange ackRange;
        while ((ackRange = ackQueue.peek()) != null) {
            if (ackRange.start == ackRange.end) {
                if (mtu < 4) {
                    break;
                }
                mtu -= 4;

                buffer.writeBoolean(true);
                buffer.writeMediumLE(ackRange.start);
            } else {
                if (mtu < 7) {
                    break;
                }
                mtu -= 7;

                buffer.writeBoolean(false);
                buffer.writeMediumLE(ackRange.start);
                buffer.writeMediumLE(ackRange.end);
            }
            ackQueue.remove();
            count++;
            written = Math.min(Integer.MAX_VALUE, written + (long) ackRange.end - ackRange.start + 1);
        }

        int finalIndex = buffer.writerIndex();
        buffer.writerIndex(lengthIndex);
        buffer.writeShort(count);
        buffer.writerIndex(finalIndex);
        return (int) written;
    }

    public static boolean verifyUnconnectedMagic(ByteBuf buffer) {
        int readerIndex = buffer.readerIndex();
        if (!verifyUnconnectedMagic(buffer, readerIndex)) {
            return false;
        }

        buffer.skipBytes(RakNetConstants.RAKNET_UNCONNECTED_MAGIC.length);
        return true;
    }

    public static boolean verifyUnconnectedMagic(ByteBuf buffer, int index) {
        byte[] magic = RakNetConstants.RAKNET_UNCONNECTED_MAGIC;
        if (index < 0 || buffer.writerIndex() - index < magic.length) {
            return false;
        }

        for (int i = 0; i < magic.length; i++) {
            if (buffer.getByte(index + i) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    public static void writeUnconnectedMagic(ByteBuf buffer) {
        buffer.writeBytes(RakNetConstants.RAKNET_UNCONNECTED_MAGIC);
    }

    public static int clamp(int value, int low, int high) {
        return value < low ? low : value > high ? high : value;
    }

    public static int powerOfTwoCeiling(int value) {
        value--;
        value |= value >> 1;
        value |= value >> 2;
        value |= value >> 4;
        value |= value >> 8;
        value |= value >> 16;
        value++;
        return value;
    }
}
