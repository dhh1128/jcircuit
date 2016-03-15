package co.codecraft.jcircuit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static co.codecraft.jcircuit.Circuit.*;


public class TimedRatioPolicyTest {

    @Test
    public void permanent_failure_is_sticky() throws InterruptedException {
        CapturingListener listener = new CapturingListener();
        //listener.debug = true;
        TimedRatioPolicy policy = TimedRatioPolicy.builder()
                .setEvalEveryNMillis(50)
                .setResetAfterNMillis(49)
                .setFailAfterNBadResets(3)
                .build();
        CircuitBreaker cb = new CircuitBreaker(policy, listener);

        /*
        For a brief time, report good health. Then report bad health for a long time, such that the
        circuit breaker tries to reset several times and eventually fails. Then simulate good health
        again. We should remain stuck in the FAILED state.
        */
        toggleSimulatedHealth(cb, 90, 780, 300);

        assertTrue(listener.transitions.size() < 20);
        assertEquals(FAILED, listener.getFinalState());
        int counts[] = listener.getTransitionCounts();
        assertTrue(counts[OPEN] >= 2);
        assertEquals(1, counts[CLOSED]);
        assertTrue(counts[RESETTING] >= 2);
        assertEquals(1, counts[FAILED]);
    }

    @Test
    public void manual_reset_clears_consecutive_failures() throws InterruptedException {
        CapturingListener listener = new CapturingListener();
        //listener.debug = true;
        TimedRatioPolicy policy = TimedRatioPolicy.builder()
                .setEvalEveryNMillis(50)
                .setResetAfterNMillis(49)
                .setFailAfterNBadResets(3)
                .build();
        final CircuitBreaker cb = new CircuitBreaker(policy, listener);

        /*
        For a brief time, report good health. Then report bad health for a long time, such that the
        circuit breaker tries to reset several times and eventually fails. Then simulate good health
        again. We would remain stuck in the FAILED state, except that on another thread, we're going
        to directly transition back to CLOSED. When that transition happens, and then we fail
        again, we should NO go to FAILED, because the number of consecutive failed resets should be
        1, not max+1.
        */
        Thread manualCloser = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(505);
                    cb.directTransition(CLOSED, true);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        manualCloser.setDaemon(true);
        manualCloser.start();
        toggleSimulatedHealth(cb,
                95,  // health goes bad near the end of 2nd eval cycle
                605, // stay bad for a number of eval cycles; we should be open by 150 ms, reset at 200, open again at
                     // 250 ms, reset again at 300, fail at 350 ms. Thereafter, we should be stuck in the failed state
                     // until a manual reset happens at 505 ms. At 550 ms, we should fail into OPEN, because we're
                     // still unhealthy. Then, at 600 ms, we should reset, and at 650 we should fail again--into OPEN,
                     // NOT into FAILED--because the directTransition() call at 505 ms should have reset consec reset
                     // failures to zero. At 700 ms, we should start reset again, and at 750, we should finally
                     // succeed.
                200 // allow 200 ms to run in the green.
        );

        listener.assertStates(CLOSED, OPEN, RESETTING, OPEN, RESETTING, OPEN, RESETTING, FAILED, CLOSED,
                OPEN, RESETTING, OPEN, RESETTING, CLOSED);
    }

    @Test
    public void complete_cycle() throws InterruptedException {
        CapturingListener listener = new CapturingListener();
        //listener.debug = true;

        TimedRatioPolicy policy = TimedRatioPolicy.builder()
            .setResetAfterNMillis(199)
            .build();

        CircuitBreaker cb = new CircuitBreaker(policy, listener);

        /*
        For a brief time, report good health. Then report bad health for a while. Then report
        good health again. If our policy is working correctly, we should flip the circuit breaker
        into an OPEN state partway through, then eventually close again. In the middle of the test,
        we should attempt to reset twice, failing once and succeeding on our second attempt. On
        machines that have plenty of idle CPU, and where we don't experience any rounding errors
        in the periodic slice timing, the timing ought to look like this:
            start (+0.0 sec)
            0.1 no change (stay closed)
            0.2 closed --> open
            0.4 open --> resetting
            0.5 resetting --> open
            0.7 open --> resetting
            0.8 resetting --> closed
        We add in some extra time just in case we run in a busy env, where the transitions are less crisp.
        */
        toggleSimulatedHealth(cb, 110, 550, 540);

        assertTrue(listener.transitions.size() < 20);
        int counts[] = listener.getTransitionCounts();
        assertTrue(counts[Circuit.OPEN] >= 1);
        assertEquals(2, counts[Circuit.CLOSED]);
        assertTrue(counts[Circuit.RESETTING] >= 1);
        assertEquals(0, counts[Circuit.FAILED]);
        assertEquals(Circuit.CLOSED, listener.getFinalState());
    }

    private void toggleSimulatedHealth(CircuitBreaker cb, Integer... periods) throws InterruptedException {
        List<Thread> threads = startWorkerThreads(cb);
        boolean b = true;
        for (Integer n: periods) {
            Thread.sleep(n);
            b = !b;
            shouldSimulateHealth.set(b);
        }
        shouldContinue.set(false);
        // Wait for all worker threads to stop.
        for (Thread th: threads) {
            th.join();
        }
    }


    private final AtomicBoolean shouldSimulateHealth = new AtomicBoolean(true);
    private final AtomicBoolean shouldContinue = new AtomicBoolean(true);

    /**
     * Create some worker threads that will send pulses through the circuit breaker and report
     * health according to what shouldSimulateHealth says.
     */
    private List<Thread> startWorkerThreads(final CircuitBreaker cb) {
        shouldSimulateHealth.set(true);
        shouldContinue.set(true);
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < 5; ++i) {
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (shouldContinue.get()) {
                        if (cb.shouldTryNormalPath()) {
                            if (shouldSimulateHealth.get()) {
                                cb.onGoodPulse();
                            } else {
                                cb.onBadPulse(null);
                            }
                        } else {
                            cb.onAltPulse();
                        }
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        return threads;
    }

}
