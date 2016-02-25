package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides a very simple circuit breaker policy, where all rules are expressed in terms of
 * counts. Reset attempts can be configured to remain in limbo until a specified number of
 * good pulses, but will fail on any bad pulse.
 */
public class CountedTransitionPolicy implements TransitionPolicy {

    /** Open a closed circuit breaker if we see this many bad pulses in a row. */
    public final long openAfterNBads;
    /** Try to reset an open circuit breaker after we see this many alt pulses in a row. */
    public final long tryResetAfterNAlts;
    /** Fail a circuit breaker after attempting to reset, and failing, this many times in a row. */
    public final long failAfterNBadResets;
    /** Deem a circuit breaker "repaired" and close it if, while resetting, we see good pulses this many times in a row. */
    public final long acceptResetAfterNGoods;

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
     * @param openAfterNBads  Must be positive. See {@link CountedTransitionPolicy#openAfterNBads the member variable}
     * @param tryResetAfterNAlts  If < 1, automatic reset is disabled. See {@link CountedTransitionPolicy#tryResetAfterNAlts the member variable}
     * @param acceptResetAfterNGoods  Must be positive. See {@link CountedTransitionPolicy#acceptResetAfterNGoods the member variable}
     * @param failAfterNBadResets  If < 1, fail is disabled. See {@link CountedTransitionPolicy#failAfterNBadResets the member variable}
     */
    public CountedTransitionPolicy(long openAfterNBads, long tryResetAfterNAlts, long acceptResetAfterNGoods, long failAfterNBadResets) {
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
