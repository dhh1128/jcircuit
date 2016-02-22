package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides a very simple circuit breaker policy, where all rules are expressed in terms of
 * counts -- if we get N errors in a row, open the circuit. After M alt pulses, try to reset.
 */
public class CountedTransitionPolicy implements TransitionPolicy {

    public final long openAfterNBads;
    public final long tryResetAfterNAlts;
    public final long failAfterNBadResets;
    public final long acceptResetAfterNGoods;
    public final AtomicLong consecutiveGoodsOrBads = new AtomicLong(0);
    public final AtomicLong consecutiveAlts = new AtomicLong(0);
    public final AtomicLong consecutiveBadResets = new AtomicLong(0);

    /**
     * Establish numeric thresholds that embody our policy.
     * @param openAfterNBads  Must be positive.
     * @param tryResetAfterNAlts  If < 1, automatic reset is disabled.
     * @param failAfterNBadResets  If < 1, fail is disabled.
     * @param acceptResetAfterNGoods  Must be positive.
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
                    if (cg >= openAfterNBads) {
                        if (cb.transition(stateSnapshot, CircuitBreaker.OPEN_STATE)) {
                            return;
                        }
                    }
                    break;
                default:
                    return;
            }
        }
    }

    public void onAltPulse(CircuitBreaker cb) {
        consecutiveAlts.incrementAndGet();
    }

}
