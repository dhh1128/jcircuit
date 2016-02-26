package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides a simple circuit breaker policy, where all rules are expressed in terms of
 * ratios of good and bad pulses.
 *
 * In terms of efficiency, this policy is suitable for highly concurrent environments and
 * very fast pulses (hundreds or thousands of pulses per second). Its overhead is low, and
 * it is robust and well tested. However, it is important to match the transition thresholds
 * in the constructor to the concurrency you expect. An env where dozens or hundreds of
 * threads are sending concurrent pulses is likely to create very "fuzzy" transitions, where
 * good and bad pulses intermingle, even if the condition that determines goodness/badness
 * is crisp. This is because concurrent pulses are likely to be processed in an order that's
 * moderately different from the order in which they actually occur. In practical terms,
 * that means you shouldn't use low thresholds (e.g., open the circuit after 5 bad pulses in
 * a row, and sucessfully reset after 5 good ones) where concurrency and pulse rate are
 * high.
 */
public class RatioDrivenTransitionPolicy implements TransitionPolicy {

    /**
     * Open a closed circuit breaker if we see a ratio of good:bad that is <= this value.
     */
    public final float openAtGoodToBadRatio;

    /**
     * Deem an open circuit breaker "repaired" and close it if, while resetting, we see a ratio of
     * good:bad pulses >= this value.
     */
    public final float closeAtGoodToBadRatio;

    /**
     * Fail a circuit breaker after attempting to reset (and failing), this many times in a row. If
     * this value is <= 0, the circuit breaker never fails -- it just flips between closed and open.
     */
    public final int failAfterNBadResets;

    /**
     * Analyze ratios in slices (sets of pulses) of this size.
     */
    public final int sliceSize;

    /**
     * How many slices do we have to wait before being eligible for a reset of the circuit breaker?
     */
    public final int eligibleForResetAfterNSlices;

    /**
     *  Holds a counter, with the lower 2 bits reserved to track which type of pulse we are counting (see
     *  {@link #GOOD_PULSE}, {@link #BAD_PULSE}, and {@link #ALT_PULSE}).
     */
    private final AtomicLong consecutivePulses = new AtomicLong(0);
    private static final long GOOD_PULSE = 0;
    private static final long BAD_PULSE = 1;
    private static final long ALT_PULSE = 2;
    private static final long PULSE_TYPE_MASK = 0x03;
    private static final long PULSE_COUNT_MASK = ~PULSE_TYPE_MASK;

    private final AtomicLong consecutiveBadResets = new AtomicLong(0);

    /**
     * Establish numeric thresholds that embody our policy.
     * @param openAfterNBads  Must be positive. See {@link RatioDrivenTransitionPolicy#openAfterNBads the member variable}
     * @param tryResetAfterNAlts  If < 1, automatic reset is disabled. See {@link RatioDrivenTransitionPolicy#tryResetAfterNAlts the member variable}
     * @param acceptResetAfterNGoods  Must be positive. See {@link RatioDrivenTransitionPolicy#acceptResetAfterNGoods the member variable}
     * @param failAfterNBadResets  If < 1, fail is disabled. See {@link RatioDrivenTransitionPolicy#failAfterNBadResets the member variable}
     */
    public RatioDrivenTransitionPolicy(long openAfterNBads, long tryResetAfterNAlts, long acceptResetAfterNGoods, long failAfterNBadResets) {
        if (openAfterNBads < 1) {
            throw new IllegalArgumentException(
                    "openAfterNBads must be positive; otherwise, circuit breaker is useless because it can never open.");
        }
        if (acceptResetAfterNGoods < 1) {
            throw new IllegalArgumentException("acceptResetAfterNGoods must be positive; otherwise, reset can never succeed.");
        }
        this.openAfterNBads = openAfterNBads;
        this.tryResetAfterNAlts = tryResetAfterNAlts;
        this.acceptResetAfterNGoods = acceptResetAfterNGoods;
        this.failAfterNBadResets = failAfterNBadResets;
    }

    private static final long getPulseType(long masked) {
        return masked & PULSE_TYPE_MASK;
    }

    private static final long getPulseCount(long masked) {
        return (masked & PULSE_COUNT_MASK) >> 2;
    }

    public boolean shouldReset(CircuitBreaker cb) {
        long n = consecutivePulses.get();
        if (getPulseType(n) == ALT_PULSE) {
            if (getPulseCount(n) >= tryResetAfterNAlts) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param type  see {@link #GOOD_PULSE}, {@link #BAD_PULSE}, or {@link #ALT_PULSE}.
     * @return The new count.
     */
    private long incrementConsecutive(long type) {
        // Loop until we can update our atomic without interference.
        while (true) {
            long n;
            long old = consecutivePulses.get();
            long oldType = getPulseType(old);
            long oldCount = getPulseCount(old);
            if (type == GOOD_PULSE) {
                n = (oldType == GOOD_PULSE) ? (oldCount + 1) & PULSE_COUNT_MASK: 1;
            } else if (type == BAD_PULSE) {
                n = (oldType == BAD_PULSE) ? (oldCount + 1) & PULSE_COUNT_MASK: 1;
            } else /*type == ALT_PULSE*/ {
                n = (oldType == ALT_PULSE) ? (oldCount + 1) & PULSE_COUNT_MASK: 1;
            }
            if (consecutivePulses.compareAndSet(old, (n << 2) | type)) {
                return n;
            }
            //System.out.println("retry in incrementConsecutive (this is not a bug, but it should happen infrequently if the system is working");
        }
    }

    public void onGoodPulse(CircuitBreaker cb) {
        int stateSnapshot = cb.getStateSnapshot();
        int state = (stateSnapshot & CircuitBreaker.STATE_MASK);

        /*
        // Occasionally, signals will be processed out of order. This can cause us to get a "good pulse"
        // signal when we're in states where that is nonsensical...
        if (state == CircuitBreaker.OPEN_STATE) {
            // Treat it as an alt pulse instead.
            onAltPulse(cb);
            return;
        } else if (state == CircuitBreaker.FAILED_STATE) {
            return;
        }*/

        long cg = incrementConsecutive(GOOD_PULSE);
        // Loop until we have reacted to the possible need for a reset correctly.
        while (true) {
            // Most of the time, we're not considering a reset, so we can exit early.
            if ((stateSnapshot & CircuitBreaker.STATE_MASK) != CircuitBreaker.RESETTING_STATE) {
                return;
            }
            // Okay, we're currently evaluating a reset. Have we seen enough goods to accept it?
            if (cg >= acceptResetAfterNGoods) {
                if (cb.transition(stateSnapshot, CircuitBreaker.CLOSED_STATE)) {
                    consecutiveBadResets.set(0);
                    return;
                }
            }
            // If we get here, then we tried to reset but someone else modified the state before we
            // could--and the new state wasn't CLOSED, because transition would have returned true
            // in that case. Re-examine our assumptions.
            System.out.printf("retry in onGoodPulse; cg = %d\n", cg);
            // Re-fetch state.
            stateSnapshot = cb.getStateSnapshot();
        }
    }

    public void onBadPulse(CircuitBreaker cb, Throwable e) {
        int stateSnapshot = cb.getStateSnapshot();
        int state = (stateSnapshot & CircuitBreaker.STATE_MASK);

        /*
        // Occasionally, signals will be processed out of order. This can cause us to get a "bad pulse"
        // signal when we're in states where that is nonsensical...
        if (state == CircuitBreaker.OPEN_STATE) {
            // treat it as an alt pulse instead
            onAltPulse(cb);
            return;
        } else if (state == CircuitBreaker.FAILED_STATE) {
            return;
        }*/

        // If we get here (the common case), then we need to react to the badness of this pulse.
        long cg = incrementConsecutive(BAD_PULSE);
        long n = -1;
        while (true) {
            switch (state) {
                // It is important to ponder why cb.transition() might fail below. In each case, it is because,
                // by the time we attempt to transition, another thread has already transitioned us out of the
                // state that we fetched when we called cb.getStateSnapshot(). Failures don't happen if a
                // transition is simply redundant, so whatever the new state is, it's truly different. If we
                // started processing the bad pulse on one thread, then got pre-empted, and when we resumed,
                // the state was different, then the correct behavior is to start over. We don't want to
                // re-increment, however.
                case CircuitBreaker.RESETTING_STATE:
                    if (consecutiveBadResets.incrementAndGet() >= failAfterNBadResets) {
                        if (!cb.transition(stateSnapshot, CircuitBreaker.FAILED_STATE)) {
                            // We must have received a GOOD pulse that transitioned us to CLOSED instead. That would
                            // have set consecutiveBadResets to 0, so all we have to do is restart.
                            break;
                        }
                    } else {
                        if (!cb.transition(stateSnapshot, CircuitBreaker.OPEN_STATE)) {
                            // We must have received another BAD pulse that pushed us over into FAILED. All we have
                            // to do is restart.
                            break;
                        }
                    }
                    // If we got here, we transitioned and can stop looping.
                    return;
                case CircuitBreaker.CLOSED_STATE:
                    if (cg >= openAfterNBads) {
                        if (!cb.transition(stateSnapshot, CircuitBreaker.OPEN_STATE)) {
                            // It's hard to construct a case where this could happen unless this thread has been
                            // pre-empted for a weirdly long time. We would have had to transition to OPEN on another
                            // state, and thence to RESETTING or beyond -- all before this thread resumed. In any
                            // case, the best we can do is restart our analysis.
                            break;
                        }
                    }
                    return;
                default:
                    return;
            }
            System.out.printf("retry in onBadPulse; cg = %d\n", cg);
            // Re-fetch state.
            stateSnapshot = cb.getStateSnapshot();
        }
    }

    public void onAltPulse(CircuitBreaker cb) {
        incrementConsecutive(ALT_PULSE);
    }

    @Override
    public boolean shouldDebug() {
        return true;
    }

}
