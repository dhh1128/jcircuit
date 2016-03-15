package co.codecraft.jcircuit;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static co.codecraft.jcircuit.Circuit.*;

/**
 * <p>Provides a simple transition policy where all rules are expressed in terms of
 * ratios of good and bad pulses, calculated at regularly timed intervals. This policy is suitable
 * for implementing transition rules such as:
 * "Open a circuit if we see <em style="color:red">X</em>% failures in a given time slice of
 * <em style="color:red">Y</em> milliseconds. Reset after <em style="color:red">Z</em> milliseconds
 * to try again."</p>
 *
 * <p>In terms of efficiency, this policy is suitable for highly concurrent environments and
 * very fast pulses (hundreds or thousands of pulses per second). Its overhead is low, and
 * it is robust and well tested. However, it is important to match the transition thresholds
 * in the constructor to the concurrency you expect. An env where dozens or hundreds of
 * threads are sending concurrent pulses is likely to create very "fuzzy" transitions, where
 * good and bad pulses intermingle, even if the condition that determines goodness/badness
 * is crisp. This is because concurrent pulses are likely to be processed in an order that's
 * moderately different from the order in which they actually occur. In practical terms,
 * that means you shouldn't use low thresholds (e.g., open the circuit after 5 bad pulses in
 * a row, and sucessfully reset after 5 good ones) where concurrency and pulse rate are
 * high.</p>
 */
public class TimedRatioPolicy extends TransitionPolicy {

    /**
     * Open a closed circuit breaker if we see a ratio of good:all that is &lt;= this value.
     * A value between 0.0 and 1.0, inclusive.
     */
    public final float openAtGoodRatio;

    /**
     * Deem an open circuit breaker "repaired" and close it if, while resetting, we see a ratio of
     * good:all pulses &gt;= this value. A value between 0.0 and 1.0, inclusive. Generally, this value
     * will be greater than {@link #openAtGoodRatio}, so a reset doesn't succeed unless the circuit
     * is likely to stay closed.
     */
    public final float closeAtGoodRatio;

    /**
     * Fail a circuit breaker after attempting to reset (and failing), this many times in a row. If
     * this value is &lt;= 0, the circuit breaker never fails -- it just transitions from {@link Circuit#RESETTING}
     * to either {@link Circuit#CLOSED} or {@link Circuit#OPEN}.
     */
    public final int failAfterNBadResets;

    /**
     * Determines how often we cut data into a "slice" against which ratios can be evaluated, and thus
     * the max speed with which transitions in the circuit can occur.
     *
     * Pick a value that allows sufficient data to accumulate that we can reason with confidence about
     * the state of the system. If your circuit will pulse 10 times per second, you probably
     * don't want to slice every 50 milliseconds, because you'll have empty slices half the time; a
     * better value in such a scenario might be 1000 or more, since this would give the average
     * slice 10 pulses to reason about. ({@link #minSliceCount} guarantees useful ratios even if no
     * samples accumulate in a given slice interval, so configuring this value correctly is mostly
     * a matter of efficiency.)
     */
    public final int evalEveryNMillis;

    /**
     * <p>Determines when we are ready to attempt a reset, after having an open circuit for a while.</p>
     *
     * <p>Resets are only attempted on eval boundaries, since a full eval cycle must complete to generate enough
     * numbers to evaluate ratios with confidence. Maturity for a reset attempt is tested by pure elapsed time
     * at the end of each eval cycle. Therefore, it is recommended that this number be chosen using the
     * formula <code>(evalEveryNMillis * multiplier) - 1</code>. This is because testing reveals that sometimes
     * periodic evaluation is early by a few microseconds. In a case where, for example, <code>evalEveryNMillis</code>
     * is 100, if <code> resetAfterNMillis</code> were also set to 100, the first eval cycle would execute, compare
     * elapsed time to 100 millis, and conclude that (very) slightly less than 100 millis had elapsed, so we would not
     * be mature for a reset attempt.</p>
     */
    public final int resetAfterNMillis;

    /**
     * The minimum number of pulses that must be seen to make a slice valid for analysis.
     *
     * Reasoning about ratios may be premature if the sample size is overly small; this value is used
     * to extend the scope of a slice until at least this many good or bad pulses are observed.
     */
    public final int minSliceCount;

    private final AtomicLong pulseCount = new AtomicLong(0);
    private final AtomicLong timeAtLastTransition = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger consecutiveBadResets = new AtomicInteger(0);
    private static final long GOOD_MASK = 0x7FFFFFFFL;
    private static final long BAD_MASK = 0x7FFFFFFF00000000L;

    private ScheduledFuture<?> slicer;

    /**
     * Establish numeric thresholds that embody our policy.
     *
     * @param openAtGoodRatio  See {@link #openAtGoodRatio the member variable}.
     * @param closeAtGoodRatio  See {@link #closeAtGoodRatio the member variable}.
     * @param minSliceCount  See {@link #minSliceCount the member variable}.
     * @param evalEveryNMillis  See {@link #evalEveryNMillis the member variable}.
     * @param resetAfterNMillis  See {@link #resetAfterNMillis the member variable}.
     * @param failAfterNBadResets  See {@link #failAfterNBadResets the member variable}.
     */
    public TimedRatioPolicy(double openAtGoodRatio, double closeAtGoodRatio, int minSliceCount,
                            int evalEveryNMillis, int resetAfterNMillis, int failAfterNBadResets) {
        if (openAtGoodRatio < 0.0 || openAtGoodRatio > 1.0) {
            throw new IllegalArgumentException("openAtGoodRatio must be >= 0.0 and <= 1.0");
        }
        if (closeAtGoodRatio < 0.0 || closeAtGoodRatio > 1.0) {
            throw new IllegalArgumentException("closeAtGoodRatio must be >= 0.0 and <= 1.0");
        }
        if (minSliceCount < 1) {
            throw new IllegalArgumentException("minSliceCount must be >= 1");
        }
        if (evalEveryNMillis < 1) {
            throw new IllegalArgumentException("evalEveryNMillis must be >= 1");
        }
        if (resetAfterNMillis < 1) {
            throw new IllegalArgumentException("resetAfterNMillis must be >= 1");
        }
        this.openAtGoodRatio = (float)openAtGoodRatio;
        this.closeAtGoodRatio = (float)closeAtGoodRatio;
        this.failAfterNBadResets = failAfterNBadResets;
        this.evalEveryNMillis = evalEveryNMillis;
        this.resetAfterNMillis = resetAfterNMillis;
        this.minSliceCount = minSliceCount;
        slicer = CircuitBreaker.scheduledExecutorService.scheduleAtFixedRate(new Slicer(this),
                evalEveryNMillis, evalEveryNMillis, TimeUnit.MILLISECONDS);
    }


    private boolean transition(int oldSnapshot, int newState, long now) {
        if (circuit.transition(oldSnapshot, newState)) {
            timeAtLastTransition.set(now);
            if (newState == CLOSED) {
                consecutiveBadResets.set(0);
            }
            return true;
        }
        return false;
    }

    /**
     * Runs on background thread. Never runs in more than one thread at the same time.
     */
    private void finalizeSlice() {

        try {
            // Tabulate our stats.
            long n = pulseCount.getAndSet(0);
            long good = n & GOOD_MASK;
            long bad = (n & BAD_MASK) >> 32;
            long all = good + bad;
            double ratio = (all > 0) ? good / (double) all : 0.0;
            long elapsed;

            // First, handle the case where we haven't observed enough pulses to evaluate anything.
            if (all < minSliceCount) {
                while (true) {
                    // It may be the case that enough time has elapsed for us to move from OPEN to RESETTING,
                    // despite the lack of pulses...
                    int x = circuit.getStateSnapshot();
                    if ((x & STATE_MASK) == OPEN) {
                        long now = System.currentTimeMillis();
                        elapsed = now - timeAtLastTransition.get();
                        if (elapsed >= resetAfterNMillis) {
                            if (!transition(x, RESETTING, now)) {
                                continue;
                            }
                        }
                    } else {
                        // Put back the count that we swapped out, so we can add to it.
                        pulseCount.addAndGet(n);
                        //System.out.println("Not enough data yet; extending slice.");
                    }
                    return;
                }
            }

            // Okay, we saw a reasonable number of pulses and can thus evaluate the health of the circuit.
            int cbr = -1;
            while (true) {
                long now = System.currentTimeMillis();
                elapsed = now - timeAtLastTransition.get();
                int x = circuit.getStateSnapshot();
                switch (x & STATE_MASK) {
                    case OPEN:
                        if (elapsed >= resetAfterNMillis) {
                            if (!transition(x, RESETTING, now)) {
                                //System.out.println("Have to retry transition to RESETTING.");
                                continue;
                            }
                        }
                        return;
                    case CLOSED:
                        if (ratio < openAtGoodRatio) {
                            if (!transition(x, OPEN, now)) {
                                //System.out.println("Have to retry transition to OPEN.");
                                continue;
                            }
                        }
                        return;
                    case RESETTING:
                        // Do things look good enough to close the circuit?
                        if (ratio >= closeAtGoodRatio) {
                            if (transition(x, CLOSED, now)) {
                                return;
                            } else {
                                //System.out.println("Have to retry transition to CLOSED.");
                                continue;
                            }
                        }
                        // We didn't close the circuit. Should we should fail it instead?
                        if (failAfterNBadResets > 0) {
                            if (cbr == -1) {
                                cbr = consecutiveBadResets.incrementAndGet();
                            }
                            if (cbr >= failAfterNBadResets) {
                                if (transition(x, FAILED, now)) {
                                    return;
                                } else {
                                    //System.out.println("Have to retry transition to FAILED.");
                                    continue;
                                }
                            }
                        }
                        // We need to re-open the circuit.
                        if (!transition(x, OPEN, now)) {
                            continue;
                        }
                        return;
                    default:
                        break;
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static class Slicer implements Runnable {
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
        pulseCount.addAndGet(1L << 32);
    }

    public void onAltPulse() {
        // do nothing
    }

    public static Builder builder() {
        return new Builder();
    }

    /*
    private static long startTimeMillis = System.currentTimeMillis();
    public static String getTimestamp() {
        return String.format("%.3f", ((System.currentTimeMillis() - startTimeMillis) % 10000) / 1000.0);
    }
    */

    /**
     * A convenience class to make constructor parameters less opaque.
     */
    public static class Builder {
        private double openAtGoodRatio = 0.5;
        private double closeAtGoodRatio = 0.75;
        private int minSliceCount = 10;
        private int evalEveryNMillis = 100;
        private int resetAfterNMillis = 99;
        private int failAfterNBadResets = 0;

        public Builder setOpenAtGoodRatio(double value) {
            openAtGoodRatio = value;
            return this;
        }
        public Builder setCloseAtGoodRatio(double value) {
            closeAtGoodRatio = value;
            return this;
        }
        public Builder setMinSliceCount(int value) {
            minSliceCount = value;
            return this;
        }
        public Builder setEvalEveryNMillis(int value) {
            evalEveryNMillis = value;
            return this;
        }
        public Builder setResetAfterNMillis(int value) {
            resetAfterNMillis = value;
            return this;
        }
        public Builder setFailAfterNBadResets(int value) {
            failAfterNBadResets = value;
            return this;
        }
        public TimedRatioPolicy build() {
            return new TimedRatioPolicy(openAtGoodRatio, closeAtGoodRatio, minSliceCount,
                    evalEveryNMillis, resetAfterNMillis, failAfterNBadResets);
        }
    }

    @Override
    public void afterDirectTransition(int desiredState, boolean force) {
        timeAtLastTransition.set(System.currentTimeMillis());
        if (desiredState == CLOSED) {
            consecutiveBadResets.set(0);
        }
    }
}
