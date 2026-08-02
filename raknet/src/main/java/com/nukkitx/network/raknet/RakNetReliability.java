package com.nukkitx.network.raknet;

import lombok.Getter;

@Getter
public enum RakNetReliability {
    UNRELIABLE(false, false, false, false, 0),
    UNRELIABLE_SEQUENCED(false, false, true, false, 1),
    RELIABLE(true, false, false, false, 2),
    RELIABLE_ORDERED(true, true, false, false, 3),
    RELIABLE_SEQUENCED(true, false, true, false, 4),
    UNRELIABLE_WITH_ACK_RECEIPT(false, false, false, true, 0),
    RELIABLE_WITH_ACK_RECEIPT(true, false, false, true, 2),
    RELIABLE_ORDERED_WITH_ACK_RECEIPT(true, true, false, true, 3);

    private static final RakNetReliability[] VALUES = values();

    final boolean reliable;
    final boolean ordered;
    final boolean sequenced;
    final boolean withAckReceipt;
    final int id;
    final int size;

    RakNetReliability(boolean reliable, boolean ordered, boolean sequenced, boolean withAckReceipt, int id) {
        this.reliable = reliable;
        this.ordered = ordered;
        this.sequenced = sequenced;
        this.withAckReceipt = withAckReceipt;
        this.id = id;

        int size = 0;
        if (this.reliable) {
            size += 3;
        }

        if (this.sequenced) {
            size += 3;
        }

        if (this.ordered || this.sequenced) {
            size += 4;
        }
        this.size = size;
    }

    public static RakNetReliability fromId(int id) {
        if (id < 0 || id > 4) {
            return null;
        }
        return VALUES[id];
    }
}
