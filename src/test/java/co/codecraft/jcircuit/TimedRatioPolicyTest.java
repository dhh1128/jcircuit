package co.codecraft.jcircuit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class TimedRatioPolicyTest {

    @Test
    public void test_permanent_failure() throws InterruptedException {
        CapturingListener listener = new CapturingListener();
        //listener.debug = true;
        TimedRatioPolicy policy = TimedRatioPolicy.builder()
                .build();
        CircuitBreaker cb = new CircuitBreaker(policy, listener);

        /*
        For a brief time, report good health. Then report bad health for a long time, such that the
        circuit breaker tries to reset several times and eventually fails. Then simulate good health
        again. We should get stuck in the FAILED state.
        */
        toggleSimulatedHealth(cb, 90, 420, 300);

        assertTrue(listener.transitions.size() < 20);
        assertEquals(Circuit.FAILED, listener.getFinalState());
        int counts[] = listener.getTransitionCounts();
        assertTrue(counts[Circuit.OPEN] >= 3);
        assertEquals(1, counts[Circuit.CLOSED]);
        assertTrue(counts[Circuit.RESETTING] >= 3);
        assertEquals(1, counts[Circuit.FAILED]);
    }

    @Test
    public void test_complete_cycle() throws InterruptedException {
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
