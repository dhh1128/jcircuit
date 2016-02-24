package co.codecraft.jcircuit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static co.codecraft.jcircuit.CircuitBreakerTest.*;
import static co.codecraft.jcircuit.CircuitBreaker.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class CountedTransitionPolicyTest {

    @Test
    public void test_CountedTransitionPolicy_simple() {
        CountedTransitionPolicy policy = new CountedTransitionPolicy(1, 1, 2, 1);
        final List<Integer> states = new ArrayList<Integer>();
        final CircuitBreaker cb = new CircuitBreaker(policy, new StateCaptureListener());

        assertTrue(cb.shouldTryNormalPath());

        // go from closed to open
        assertEquals(CLOSED_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
        cb.onGoodPulse(); // closed --> closed
        assertEquals(CLOSED_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
        cb.onBadPulse(null); // closed --bad--> open
        assertEquals(OPEN_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
        assertFalse(cb.shouldTryNormalPath());

        // Healthy reset sequence
        cb.onAltPulse(); // open --> about to reset
        assertEquals(OPEN_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
        assertTrue(cb.shouldTryNormalPath());
        assertEquals(RESETTING_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
        cb.onGoodPulse();
        assertEquals(CLOSED_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);

        // Now a failed reset sequence
        cb.onBadPulse(null);
        assertEquals(OPEN_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
        cb.onAltPulse();
        assertTrue(cb.shouldTryNormalPath());
        cb.onBadPulse(null); // first failed reset
        assertFalse(cb.shouldTryNormalPath());
        assertEquals(OPEN_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
        cb.onAltPulse();
        assertTrue(cb.shouldTryNormalPath());
        cb.onBadPulse(null);
        assertEquals(FAILED_STATE, cb.getStateSnapshot() & CircuitBreaker.STATE_MASK);
    }

    @Test
    public void test_CountedTransitionPolicy_no_fails() throws InterruptedException {
        CountedTransitionPolicy policy = new CountedTransitionPolicy(3, 2, 3, 4);
        StateCaptureListener listener = new StateCaptureListener();
        final CircuitBreaker cb = new CircuitBreaker(policy, listener);
        List<Thread> threads = new ArrayList<Thread>();

        final AtomicInteger stateIndex = new AtomicInteger(0);
        // Create some threads that will call the circuit breaker. We will send 1001 pulses
        // through the circuit breaker. Most will be good; a few will be bad or alt.
        for (int i = 0; i < 5; ++i) {
            Thread th = new Thread(new Runnable() {
                public void run() {
                    for (int j = 0; j < 1001; ++j) {
                        if (cb.shouldTryNormalPath()) {
                            int n = stateIndex.getAndIncrement();
                            int m = n % 100;
                            if (m == 0 || m == 97) {
                                //System.out.printf("%d\n", n);
                            }
                            if (m < 97) {
                                // 0 (reset attempt that succeeds), 1 ... 96
                                cb.onGoodPulse();
                            } else {
                                // 97 --> open, 98, 99
                                cb.onBadPulse(null);
                            }
                        } else {
                            cb.onAltPulse();
                        }
                        try {
                            Thread.sleep(0, 10000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            return;
                        }
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (Thread th: threads) {
            th.join();
        }
        assertEquals(CircuitBreaker.CLOSED_STATE, (int)listener.states.get(listener.states.size() - 1));
    }
}
