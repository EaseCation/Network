package com.nukkitx.network.raknet;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Generate and verify stateless cookies for offline handshake.
 * <p>
 * SipHash implementation based on the reference SipHash-2-4 implementation at
 * https://github.com/veorq/SipHash, which is multi-licensed under:
 * CC0-1.0: https://github.com/veorq/SipHash/blob/master/LICENCE_CC0
 * MIT: https://github.com/veorq/SipHash/blob/master/LICENSE_MIT
 * Apache-2.0: https://github.com/veorq/SipHash/blob/master/LICENSE_A2LLVM
 */
final class RakNetCookie {
    private static final long MINUTE_MS = 60_000;
    private static final long KEY_EPOCH_MS = 10 * MINUTE_MS;
    private static final int MINIMUM_SECRET_SIZE = 16;

    private final byte[] secret;
    private final LongSupplier clock;
    private final Object cacheLock = new Object();
    private volatile KeyCache cache = new KeyCache(-1, null, -1, null);

    RakNetCookie(byte[] secret) {
        this(secret, System::currentTimeMillis);
    }

    RakNetCookie(byte[] secret, LongSupplier clock) {
        if (secret == null || secret.length < MINIMUM_SECRET_SIZE) {
            throw new IllegalArgumentException("Cookie secret must be at least 16 bytes");
        }
        this.secret = secret.clone();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    int generate(InetSocketAddress sender, int protocolVersion) {
        if (protocolVersion < 1 || protocolVersion > 16) {
            throw new IllegalArgumentException("Protocol version must be between 1 and 16");
        }

        long now = this.clock.getAsLong();
        // 4 bits timestamp (16 minutes cycle)
        int timestamp = (int) ((now / MINUTE_MS) & 0x0f);
        // High bits: Timestamp
        // Low bits: Protocol Version (Mapped 1-16 -> 0-15)
        int combined = timestamp << 4 | protocolVersion - 1;
        HashKey key = this.getKey(now / KEY_EPOCH_MS);
        // Cookie = [Signature (24 bits) | Timestamp (4 bits) | Protocol (4 bits)]
        return (this.computeSignature(sender, combined, key) << 8) | combined;
    }

    boolean validate(int cookie, InetSocketAddress sender) {
        int combined = cookie & 0xff;
        int timestamp = combined >>> 4;
        int receivedSignature = cookie >>> 8 & 0xffffff;

        // Verify timestamp (All modes except OFF)
        long now = this.clock.getAsLong();
        long currentMinute = now / MINUTE_MS;
        int currentTimestamp = (int) (currentMinute & 0x0f);
        int minuteDifference = currentTimestamp - timestamp & 0x0f; // Wrap-around 4 bits

        // (0 = current, 1 = previous, etc.)
        // If diff is small positive, it's recent past.
        if (minuteDifference > 1) { // 2 minutes
            return false;
        }

        // Reconstruct epoch from the timestamp in the cookie
        // We calculate the absolute time when the cookie was likely generated
        long originalTime = now - minuteDifference * MINUTE_MS;

        // ACTIVE or OFFLOADED_PSK
        HashKey key = this.getKey(originalTime / KEY_EPOCH_MS);
        return receivedSignature == this.computeSignature(sender, combined, key);
    }

    static int getProtocolVersion(int cookie) {
        // Low 4 bits + 1
        return (cookie & 0x0f) + 1;
    }

    private int computeSignature(InetSocketAddress sender, int combined, HashKey key) {
        byte[] address = sender.getAddress().getAddress();
        // Data = [IP (4/16) | Port (2) | Timestamp (1)]
        byte[] data = new byte[address.length + 3];
        System.arraycopy(address, 0, data, 0, address.length);
        int index = address.length;
        data[index++] = (byte) (sender.getPort() >>> 8);
        data[index++] = (byte) sender.getPort();
        data[index] = (byte) combined;
        return (int) this.hash(data, key.first, key.second) & 0xffffff; // Truncate to 24 bits
    }

    private HashKey getKey(long epoch) {
        KeyCache current = this.cache;
        if (current.firstEpoch == epoch) {
            return current.firstKey;
        }
        if (current.secondEpoch == epoch) {
            return current.secondKey;
        }

        synchronized (this.cacheLock) {
            current = this.cache; // Double-checked locking
            if (current.firstEpoch == epoch) {
                return current.firstKey;
            }
            if (current.secondEpoch == epoch) {
                return current.secondKey;
            }

            HashKey key = this.computeKey(epoch);
            // New key becomes primary. Old primary becomes secondary.
            this.cache = new KeyCache(epoch, key, current.firstEpoch, current.firstKey);
            return key;
        }
    }

    private HashKey computeKey(long epoch) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.secret, "HmacSHA256"));
            byte[] epochBytes = new byte[Long.BYTES];
            for (int i = epochBytes.length - 1; i >= 0; i--) {
                epochBytes[i] = (byte) epoch;
                epoch >>>= Byte.SIZE;
            }

            byte[] digest = mac.doFinal(epochBytes);
            long first = 0;
            long second = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                first |= (long) (digest[i] & 0xff) << i * Byte.SIZE;
                second |= (long) (digest[i + Long.BYTES] & 0xff) << i * Byte.SIZE;
            }
            return new HashKey(first, second);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to derive RakNet cookie key", exception);
        }
    }

    private long hash(byte[] data, long firstKey, long secondKey) {
        long[] state = {
                0x736f6d6570736575L ^ firstKey,
                0x646f72616e646f6dL ^ secondKey,
                0x6c7967656e657261L ^ firstKey,
                0x7465646279746573L ^ secondKey
        };

        int remainder = data.length & 7;
        int index = 0;
        while (index < data.length - remainder) {
            long message = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                message |= (long) (data[index + i] & 0xff) << i * Byte.SIZE;
            }
            // Process complete message blocks following the standard SipHash-2-4 compression order
            state[3] ^= message;
            sipRounds(state, 2);
            state[0] ^= message;
            index += Long.BYTES;
        }

        long tail = (long) data.length << 56;
        for (int i = 0; i < remainder; i++) {
            tail |= (long) (data[index + i] & 0xff) << i * Byte.SIZE;
        }
        state[3] ^= tail;
        sipRounds(state, 2);
        state[0] ^= tail;
        state[2] ^= 0xff;
        sipRounds(state, 4);
        return state[0] ^ state[1] ^ state[2] ^ state[3];
    }

    private static void sipRounds(long[] state, int count) {
        long first = state[0];
        long second = state[1];
        long third = state[2];
        long fourth = state[3];
        for (int i = 0; i < count; i++) {
            first += second;
            second = Long.rotateLeft(second, 13) ^ first;
            first = Long.rotateLeft(first, 32);
            third += fourth;
            fourth = Long.rotateLeft(fourth, 16) ^ third;
            first += fourth;
            fourth = Long.rotateLeft(fourth, 21) ^ first;
            third += second;
            second = Long.rotateLeft(second, 17) ^ third;
            third = Long.rotateLeft(third, 32);
        }
        state[0] = first;
        state[1] = second;
        state[2] = third;
        state[3] = fourth;
    }

    /**
     * Immutable container for caching keys.
     */
    private record KeyCache(long firstEpoch, HashKey firstKey, long secondEpoch, HashKey secondKey) {
    }

    private record HashKey(long first, long second) {
    }
}
