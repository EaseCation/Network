package com.nukkitx.network;

import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

@UtilityClass
public class VarInts {

    public static void writeInt(ByteBuf buffer, int value) {
        encode(buffer, ((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL);
    }

    public static int readInt(ByteBuf buffer) {
        int n = (int) decode(buffer, Integer.SIZE);
        return (n >>> 1) ^ -(n & 1);
    }

    public static void writeUnsignedInt(ByteBuf buffer, int value) {
        encode(buffer, value & 0xFFFFFFFFL);
    }

    public static int readUnsignedInt(ByteBuf buffer) {
        return (int) decode(buffer, Integer.SIZE);
    }

    public static void writeLong(ByteBuf buffer, long value) {
        encode(buffer, (value << 1) ^ (value >> 63));
    }

    public static long readLong(ByteBuf buffer) {
        long n = decode(buffer, Long.SIZE);
        return (n >>> 1) ^ -(n & 1);
    }

    public static void writeUnsignedLong(ByteBuf buffer, long value) {
        encode(buffer, value);
    }

    public static long readUnsignedLong(ByteBuf buffer) {
        return decode(buffer, Long.SIZE);
    }

    private static long decode(ByteBuf buffer, int bits) {
        long result = 0;
        for (int shift = 0; shift < bits; shift += 7) {
            final byte b = buffer.readByte();
            if (bits - shift < 7 && ((b & 0x7f) >>> (bits - shift)) != 0) {
                throw new ArithmeticException("Varint was too large");
            }
            result |= (b & 0x7FL) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new ArithmeticException("Varint was too large");
    }

    private static void encode(ByteBuf buffer, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                buffer.writeByte((int) value);
                return;
            } else {
                buffer.writeByte((byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }
}
