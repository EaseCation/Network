package com.nukkitx.network.raknet;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import static com.nukkitx.network.raknet.RakNetConstants.*;

public class RakNetSlidingWindow {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakNetSlidingWindow.class);

    private final int mtu;
    private final int adjustedMtu;
    private double cwnd;
    private double ssThresh;
    private double estimatedRTT = -1;
    private double lastRTT = -1;
    private double deviationRTT = -1;
    private long oldestUnsentAck;
    private long nextCongestionControlBlock;
    private boolean backoffThisBlock;
    private boolean isContinuousSend;
    private int expectedNextSequenceNumber;
    private int nextSequenceNumber;

    public RakNetSlidingWindow(int mtu, int adjustedMtu) {
        this.mtu = mtu;
        this.cwnd = mtu;
        this.adjustedMtu = adjustedMtu;
    }

    public int getRetransmissionBandwidth(int unAckedBytes) {
        return unAckedBytes;
    }

    public int getTransmissionBandwidth(int unAckedBytes, boolean isContinuousSend) {
        this.isContinuousSend = isContinuousSend;
        if (unAckedBytes <= this.cwnd) {
            return (int) (this.cwnd - unAckedBytes);
        } else {
            return 0;
        }
    }

    public int onPacketReceived(long curTime, int sequenceNumber) {
        if (this.oldestUnsentAck == 0) {
            this.oldestUnsentAck = curTime;
        }

        int sequenceDelta = RakNetUtils.getSequenceIndexDelta(sequenceNumber, this.expectedNextSequenceNumber);
        int skippedMessageCount = 0;
        if (sequenceDelta == 0) {
            this.expectedNextSequenceNumber = sequenceNumber + 1 & 0xFFFFFF;
        } else if (RakNetUtils.isSequenceIndexAhead(sequenceNumber, this.expectedNextSequenceNumber)) {
            skippedMessageCount = sequenceDelta;
            if (skippedMessageCount > 1000) {
                if (skippedMessageCount > 50000) {
                    log.debug("Too many stale data: {}", skippedMessageCount);
                    return -1;
                }
                skippedMessageCount = 1000;
            }
            this.expectedNextSequenceNumber = sequenceNumber + 1 & 0xFFFFFF;
        }
        return skippedMessageCount;
    }

    public void onResend() {
        if (this.isContinuousSend && !this.backoffThisBlock && this.cwnd > this.adjustedMtu * 2) {
            this.ssThresh = this.cwnd / 2D;

            if (this.ssThresh < this.adjustedMtu) {
                this.ssThresh = this.adjustedMtu;
            }
            this.cwnd = this.adjustedMtu;

            this.nextCongestionControlBlock = this.nextSequenceNumber;
            this.backoffThisBlock = true;
        }
    }

    public void onNak() {
        if (this.isContinuousSend && !this.backoffThisBlock) {
            this.ssThresh = this.cwnd / 2D;
            this.nextCongestionControlBlock = this.nextSequenceNumber;
            this.backoffThisBlock = true;
        }
    }

    public int getAndIncrementNextSequenceNumber() {
        int n = this.nextSequenceNumber;
        this.nextSequenceNumber = this.nextSequenceNumber + 1 & 0xFFFFFF;
        return n;
    }

    public void onAck(long rtt, long sequenceIndex, boolean isContinuousSend) {
        this.lastRTT = rtt;

        if (this.estimatedRTT == -1) {
            this.estimatedRTT = rtt;
            this.deviationRTT = rtt;
        } else {
            double d = 0.05D;
            double difference = rtt - this.estimatedRTT;
            this.estimatedRTT += d * difference;
            this.deviationRTT += d * (Math.abs(difference) - this.deviationRTT);
        }

        this.isContinuousSend = isContinuousSend;
        if (!isContinuousSend) {
            return;
        }

        boolean isNewCongestionControlPeriod = RakNetUtils.isSequenceIndexAhead(sequenceIndex, this.nextCongestionControlBlock);

        if (isNewCongestionControlPeriod) {
            this.backoffThisBlock = false;
            this.nextCongestionControlBlock = this.nextSequenceNumber;
        }

        if (this.isInSlowStart()) {
            this.cwnd += this.mtu;

            if (this.cwnd > this.ssThresh && this.ssThresh != 0) {
                this.cwnd = this.ssThresh + this.mtu * this.mtu / this.cwnd;
            }
        } else {
            this.cwnd += this.mtu * this.mtu / this.cwnd;
        }
        this.cwnd = this.cwnd > Integer.MAX_VALUE ? Integer.MAX_VALUE : this.cwnd;
    }

    public boolean isInSlowStart() {
        return this.cwnd <= this.ssThresh || this.ssThresh == 0;
    }

    public void onSendAck() {
        this.oldestUnsentAck = 0;
    }

    @SuppressWarnings("ManualMinMaxCalculation")
    public long getRtoForRetransmission() {
        if (this.estimatedRTT == -1) {
            return CC_MAXIMUM_THRESHOLD;
        }

        long threshold = (long) ((2.0D * this.estimatedRTT + 4.0D * this.deviationRTT) + CC_ADDITIONAL_VARIANCE);
        return threshold > CC_MAXIMUM_THRESHOLD ? CC_MAXIMUM_THRESHOLD : threshold;
    }

    public double getRTT() {
        return this.estimatedRTT;
    }

    public boolean shouldSendAcks(long curTime) {
        long rto = this.getSenderRtoForAck();

        return rto == -1 || curTime >= this.oldestUnsentAck + CC_SYN;
    }

    public long getSenderRtoForAck() {
        if (this.lastRTT == -1) {
            return -1;
        } else {
            return (long) (this.lastRTT + CC_SYN);
        }
    }
}
