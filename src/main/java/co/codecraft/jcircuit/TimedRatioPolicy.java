package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicLong;

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
public class TimedRatioPolicy implements TransitionPolicy {

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
     * Determines when we collate data into a "slice" against which ratios can be evaluated.
     */
    public final int sliceAfterNMillis;

    /**
     * Determines when we are ready to reset, after having an open circuit for a while.
     */
    public final int resetAfterNMillis;

    private final AtomicLong pulseCount = new AtomicLong(0);
    private static final long GOOD_PULSE = 0;
    private static final long BAD_PULSE = 1;
    private final AtomicLong consecutiveBadResets = new AtomicLong(0);

    /**
     * Establish numeric thresholds that embody our policy.
     *
     * @param openAtGoodToBadRatio  See {@link #openAtGoodToBadRatio the member variable}.
     * @param closeAtGoodToBadRatio  See {@link #closeAtGoodToBadRatio the member variable}.
     * @param failAfterNBadResets  If < 1, fail is disabled. See {@link #failAfterNBadResets the member variable}.
     */
    public TimedRatioPolicy(float openAtGoodToBadRatio, float closeAtGoodToBadRatio, int failAfterNBadResets,
                            int sliceAfterNMillis, int resetAfterNMillis) {
        this.openAtGoodToBadRatio = openAtGoodToBadRatio;
        this.closeAtGoodToBadRatio = closeAtGoodToBadRatio;
        this.failAfterNBadResets = failAfterNBadResets;
        this.sliceAfterNMillis = sliceAfterNMillis;
        this.resetAfterNMillis = resetAfterNMillis;
        CircuitBreaker.threadPool.submit(new Timeout(sliceAfterNMillis, new Slicer(this)));
    }

    private void finalizeSlice() {

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

    public static class Timeout implements Runnable {
        private int millis;
        private Runnable delegate;
        public Timeout(int millis, Runnable delegate) {
            this.millis = millis;
            this.delegate = delegate;
        }
        @Override
        public void run() {
            while (true) {
                try {
                    this.wait(millis);
                    delegate.run();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    public void onGoodPulse(CircuitBreaker cb) {
        pulseCount.incrementAndGet();

    }

    public void onBadPulse(CircuitBreaker cb, Throwable e) {
        // Loop until we can update our atomic without interference.
        while (true) {
            long n = pulseCount.get();
            // Increment just the highest 32 bits of the number. (We are storing 2 signed 32-bit numbers in the
            // same 64-bit number. The good count is in the bottom half, and the bad count is in the top half.)
            long m = (n & 0x7FFFFFFF) | ((((n >> 32) + 1) & 0x7FFFFFFF) << 32);
            if (pulseCount.compareAndSet(n, m)) {
                return;
            }
        }
    }

    public void onAltPulse(CircuitBreaker cb) {
    }

}
