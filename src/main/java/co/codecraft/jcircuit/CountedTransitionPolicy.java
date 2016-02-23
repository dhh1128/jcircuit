package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides a very simple circuit breaker policy, where all rules are expressed in terms of
 * counts. Resets fail on any bad pulse.
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

    public final AtomicLong consecutiveGoodsOrBads = new AtomicLong(0);
    public final AtomicLong consecutiveAlts = new AtomicLong(0);
    public final AtomicLong consecutiveBadResets = new AtomicLong(0);

    /**
     * Establish numeric thresholds that embody our policy.
     * @param openAfterNBads  Must be positive. See {@link CountedTransitionPolicy#openAfterNBads the member variable}
     * @param tryResetAfterNAlts  If < 1, automatic reset is disabled. See {@link CountedTransitionPolicy#tryResetAfterNAlts the member variable}
     * @param failAfterNBadResets  If < 1, fail is disabled. See {@link CountedTransitionPolicy#failAfterNBadResets the member variable}
     * @param acceptResetAfterNGoods  Must be positive. See {@link CountedTransitionPolicy#acceptResetAfterNGoods the member variable}
     */
    public CountedTransitionPolicy(long openAfterNBads, long tryResetAfterNAlts, long failAfterNBadResets,
                                   long acceptResetAfterNGoods) {
        if (openAfterNBads < 1) {
            throw new IllegalArgumentException(
                    "openAfterNBads must be positive; otherwise, circuit breaker is useless because it can never open.");
        }
        if (acceptResetAfterNGoods < 1) {
            throw new IllegalArgumentException("acceptResetAfterNGoods must be positive; otherwise, reset can never succeed.");
        }
        this.openAfterNBads = openAfterNBads;
        this.tryResetAfterNAlts = tryResetAfterNAlts;
        this.failAfterNBadResets = failAfterNBadResets;
        this.acceptResetAfterNGoods = acceptResetAfterNGoods;
    }

    public boolean shouldReset(CircuitBreaker cb) {
        return consecutiveAlts.get() >= tryResetAfterNAlts;
    }

    private long incrementConsecutiveGoodsOrBads(long n) {
        // Loop until we can update our atomic without interference.
        while (true) {
            long old = consecutiveGoodsOrBads.get();
            if (n == 1) {
                n = old > 0 ? old + 1 : 1;
            } else {
                n = old < 0 ? old - 1 : -1;
            }
            if (consecutiveGoodsOrBads.compareAndSet(old, n)) {
                return n;
            }
            System.out.println("retry in incrementConsecutiveGoodsOrBads");
        }
    }

    public void onGoodPulse(CircuitBreaker cb) {
        long cg = incrementConsecutiveGoodsOrBads(1);
        // Loop until we have reacted to the possible need for a reset correctly.
        while (true) {
            int stateSnapshot = cb.getStateSnapshot();
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
        }
    }

    public void onBadPulse(CircuitBreaker cb, Throwable e) {
        long cg = incrementConsecutiveGoodsOrBads(-1);
        while (true) {
            int stateSnapshot = cb.getStateSnapshot();
            switch (stateSnapshot & CircuitBreaker.STATE_MASK) {
                case CircuitBreaker.RESETTING_STATE:
                    if (consecutiveBadResets.incrementAndGet() >= failAfterNBadResets) {
                        if (cb.transition(stateSnapshot, CircuitBreaker.FAILED_STATE)) {
                            return;
                        }
                    } else {

                    }
                    break;
                case CircuitBreaker.CLOSED_STATE:
                    if (-cg >= openAfterNBads) {
                        if (cb.transition(stateSnapshot, CircuitBreaker.OPEN_STATE)) {
                            return;
                        }
                    }
                    break;
                default:
                    return;
            }
            System.out.printf("retry in onBadPulse; cg = %d\n", cg);
        }
    }

    public void onAltPulse(CircuitBreaker cb) {
        consecutiveAlts.incrementAndGet();
    }

}
