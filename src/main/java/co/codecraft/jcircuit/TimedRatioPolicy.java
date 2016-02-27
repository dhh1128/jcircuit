package co.codecraft.jcircuit;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static co.codecraft.jcircuit.Circuit.*;

/**
 * Provides a simple transition policy, where all rules are expressed in terms of
 * ratios of good and bad pulses.
 *
 * In terms of efficiency, this transitionPolicy is suitable for highly concurrent environments and
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
public class TimedRatioPolicy extends TransitionPolicy {

    /**
     * Open a closed circuit breaker if we see a ratio of good:all that is <= this value.
     */
    public final float openAtGoodRatio;

    /**
     * Deem an open circuit breaker "repaired" and close it if, while resetting, we see a ratio of
     * good:all pulses >= this value.
     */
    public final float closeAtGoodRatio;

    /**
     * Fail a circuit breaker after attempting to reset (and failing), this many times in a row. If
     * this value is <= 0, the circuit breaker never fails -- it just flips between closed and open.
     */
    public final int failAfterNBadResets;

    /**
     * Determines when we collate data into a "slice" against which ratios can be evaluated.
     */
    public final int sliceAfterNMillis;

    /**
     * Determines when we are ready to reset, after having an open circuit for a while.
     */
    public final int resetAfterNMillis;

    private final AtomicLong pulseCount = new AtomicLong(0);
    private final AtomicLong timeAtLastTransition = new AtomicLong(0);
    private final AtomicLong consecutiveBadResets = new AtomicLong(0);
    private static final long GOOD_MASK = 0x7FFFFFFFL;
    private static final long BAD_MASK = 0x7FFFFFFF00000000L;

    private ScheduledFuture<?> slicer;

    /**
     * Establish numeric thresholds that embody our policy.
     *
     * @param openAtGoodRatio  See {@link #openAtGoodRatio the member variable}.
     * @param closeAtGoodRatio  See {@link #closeAtGoodRatio the member variable}.
     * @param failAfterNBadResets  If < 1, fail is disabled. See {@link #failAfterNBadResets the member variable}.
     */
    public TimedRatioPolicy(CircuitBreaker cb, float openAtGoodRatio, float closeAtGoodRatio,
                            int failAfterNBadResets, int sliceAfterNMillis, int resetAfterNMillis) {
        this.openAtGoodRatio = openAtGoodRatio;
        this.closeAtGoodRatio = closeAtGoodRatio;
        this.failAfterNBadResets = failAfterNBadResets;
        this.sliceAfterNMillis = sliceAfterNMillis;
        this.resetAfterNMillis = resetAfterNMillis;
        slicer = CircuitBreaker.scheduledExecutorService.scheduleAtFixedRate(new Slicer(this),
                sliceAfterNMillis, sliceAfterNMillis, TimeUnit.MILLISECONDS);
    }

    private void finalizeSlice() {
        long n = pulseCount.getAndSet(0);
        long good = n & GOOD_MASK;
        long bad = (n & BAD_MASK) >> 32;
        double ratio = good / (bad > 0 ? bad : 1);
        while (true) {
            long elapsed;
            long now = System.currentTimeMillis();
            int x = circuit.getStateSnapshot();
            switch (x & STATE_MASK) {
                case OPEN:
                    elapsed = now - timeAtLastTransition.get();
                    if (elapsed >= resetAfterNMillis) {
                        if (circuit.transition(x, RESETTING)) {
                            timeAtLastTransition.set(now);
                            return;
                        }
                    } else {
                        return;
                    }
                    break;
                case CLOSED:
                    if (ratio < openAtGoodRatio) {
                        if (circuit.transition(x, OPEN)) {
                            timeAtLastTransition.set(now);
                            return;
                        }
                    }
                    break;
                case RESETTING:
                    elapsed = now - timeAtLastTransition.get();
                    if (elapsed >= resetAfterNMillis) {
                        if (ratio >= closeAtGoodRatio) {
                            if (circuit.transition(x, CLOSED)) {
                                return;
                            }
                        } else {
                            if (failAfterNBadResets > 0 && consecutiveBadResets.incrementAndGet() >= failAfterNBadResets) {
                                if (circuit.transition(x, FAILED)) {
                                    return;
                                }
                            }
                        }
                    } else {
                        return;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    public static class Slicer implements Runnable {
        private TimedRatioPolicy policy;
        public Slicer(TimedRatioPolicy policy) {
            this.policy = policy;
        }
        @Override
        public void run() {
            policy.finalizeSlice();
        }
    }

    public void onGoodPulse() {
        pulseCount.incrementAndGet();

    }

    public void onBadPulse(Throwable e) {
        // Loop until we can update our atomic without interference.
        while (true) {
            long n = pulseCount.get();
            // Increment just the highest 32 bits of the number. (We are storing 2 signed 32-bit numbers in the
            // same 64-bit number. The good count is in the bottom half, and the bad count is in the top half.)
            long m = (n & GOOD_MASK) | ((((n >> 32) + 1) & GOOD_MASK) << 32);
            if (pulseCount.compareAndSet(n, m)) {
                return;
            }
        }
    }

    public void onAltPulse() {
    }

}
