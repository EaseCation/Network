package com.nukkitx.network.raknet.util;

import com.nukkitx.network.raknet.EncapsulatedPacket;
import com.nukkitx.network.raknet.RakNetUtils;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;

public class FastBinaryMinHeap {
    private HeapElement[] heap;
    public long[] weights;
    private int size;

    public FastBinaryMinHeap() {
        this(8);
    }

    public FastBinaryMinHeap(int initialCapacity) {
        this.heap = new HeapElement[++initialCapacity];
        this.weights = new long[initialCapacity];
        Arrays.fill(this.weights, Long.MAX_VALUE); // infimum
        this.weights[0] = Long.MIN_VALUE; // supremum
    }

    private void resize(int capacity) {
        int adjustedSize = this.size + 1;
        int copyLength = Math.min(this.heap.length, adjustedSize);
        HeapElement[] newHeap = new HeapElement[capacity];
        long[] newWeights = new long[capacity];
        System.arraycopy(this.heap, 0, newHeap, 0, copyLength);
        System.arraycopy(this.weights, 0, newWeights, 0, copyLength);
        if (capacity > adjustedSize) {
            Arrays.fill(newWeights, adjustedSize, capacity, Long.MAX_VALUE);
        }
        this.heap = newHeap;
        this.weights = newWeights;
    }

    public void insert(long weight, EncapsulatedPacket element) {
        Objects.requireNonNull(element, "element");
        this.ensureCapacity(this.size + 1);
        this.insert0(weight, new EncapsulatedPacket[]{element});
    }

    private void insert0(long weight, EncapsulatedPacket[] element) {
        int hole = ++this.size;
        int pred = hole >> 1;
        long predWeight = this.weights[pred];

        while (predWeight > weight) {
            this.weights[hole] = predWeight;
            this.heap[hole] = this.heap[pred];
            hole = pred;
            pred >>= 1;
            predWeight = this.weights[pred];
        }

        this.weights[hole] = weight;
        this.heap[hole] = new HeapElement(element);
    }

    public void insertSeries(long weight, EncapsulatedPacket[] elements) {
        Objects.requireNonNull(elements, "elements");
        if (elements.length == 0) return;

        this.ensureCapacity(this.size + 1);

        this.insert0(weight, elements);
    }

    private void ensureCapacity(int size) {
        // +1 for infimum
        if (size + 1 >= this.heap.length) {
            this.resize(RakNetUtils.powerOfTwoCeiling(size + 1));
        }
    }

    public EncapsulatedPacket peek() {
        HeapElement e = this.heap[1];
        if (e != null) {
            return e.peek();
        }
        return null;
    }

    public EncapsulatedPacket poll() {
        if (this.size > 0) {
            EncapsulatedPacket e = this.peek();
            this.remove();
            return e;
        }
        return null;
    }

    public void remove() {
        if (this.size == 0) {
            throw new NoSuchElementException("Heap is empty");
        }
        if (this.heap[1].remove()) {
            return;
        }
        int hole = 1;
        int succ = 2;
        int sz = this.size;

        while (succ < sz) {
            long weight1 = this.weights[succ];
            long weight2 = this.weights[succ + 1];

            if (weight1 > weight2) {
                this.weights[hole] = weight2;
                this.heap[hole] = this.heap[++succ];
            } else {
                this.weights[hole] = weight1;
                this.heap[hole] = this.heap[succ];
            }
            hole = succ;
            succ <<= 1;
        }

        // bubble up rightmost element
        long bubble = this.weights[sz];
        int pred = hole >> 1;
        while (this.weights[pred] > bubble) { // must terminate since min at root
            this.weights[hole] = this.weights[pred];
            this.heap[hole] = this.heap[pred];
            hole = pred;
            pred >>= 1;
        }

        // finally move data to hole
        this.weights[hole] = bubble;
        this.heap[hole] = this.heap[sz];

        this.heap[sz] = null; // mark as deleted
        this.weights[sz] = Long.MAX_VALUE;

        this.size--;

        if ((this.size << 2) < this.heap.length && this.size > 4) {
            this.resize(this.size << 1);
        }
    }

    public boolean isEmpty() {
        return this.size == 0;
    }

    public int size() {
        return this.size;
    }

    private static class HeapElement {
        private final EncapsulatedPacket[] packets;
        private int index;

        public HeapElement(EncapsulatedPacket[] packets) {
            this.packets = packets;
        }

        public EncapsulatedPacket peek() {
            return this.packets[this.index];
        }

        public boolean remove() {
            return ++this.index < this.packets.length;
        }
    }
}
