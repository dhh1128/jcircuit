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


public class TimedRatioPolicyTest {

    @Test
    public void test_many_opens_no_fails() throws InterruptedException {
        assertFalse(true);
        /*
        create a transitionPolicy: require ratio > 0.8 to reset, <0.5 to fail
                re-evaluate every .1 secs, and for reset after .2 secs
        create 5 threads to generate pulses
        create 1 thread that toggles pulses so they are good for .25 secs, bad for .5 secs, and good for .25 secs
        assert that we have flipped to open at least once but not more than twice
        assert that we have attempted to reset more than once
        assert that we have now successfully closed again
        // openAfterNBads = 3
        // tryResetAfterNAlts = 2
        // acceptResetAfterNGoods = 4
        // failAfterNBadResets = 3
        TimedRatioPolicy transitionPolicy = new TimedRatioPolicy(3, 2, 4, 3);

        StateCaptureListener listener = new StateCaptureListener();
        final CircuitBreaker cb = new CircuitBreaker(transitionPolicy, listener);
        List<Thread> threads = new ArrayList<Thread>();

        final AtomicInteger stateIndex = new AtomicInteger(0);

        // Create some threads that will call the circuit breaker. We will send about a thousand pulses
        // through the circuit breaker. Most will be good; a few will be bad or alt.
        for (int i = 0; i < 5; ++i) {
            Thread th = new Thread(new Runnable() {
                public void run() {
                    for (int j = 0; j < 1000; ++j) {
                        if (cb.shouldTryNormalPath()) {
                            int n = stateIndex.getAndIncrement();
                            if (n % 50 == 0) {
                                System.out.printf("%d\n", n);
                            }
                            int m = n % 100;

                            // For the first 91 iterations out of every 100 (m=0 through m=90), we want to
                            // keep the circuit breaker closed. During the m=50 to m=70 range, even pulses
                            // will be bad (which shouldn't be enough to trip the circuit breaker); in the
                            // rest of the range, just report good pulses.
                            if (m <= 90 || m > 97) {

                                if ((m >= 50 && m <= 70) && (m % 2 == 0)) {
                                    cb.onBadPulse(null);
                                } else {
                                    cb.onGoodPulse();
                                }

                            // For the next 5 iterations (m=91 through m=95), report a bad pulse. When m == 93, this
                            // should trip the breaker; we go a little past that just in case something gets
                            // reported out of order.
                            } else if (m <= 95) {
                                cb.onBadPulse(null);
                            }
                        } else {
                            // This should happen for m=96 through m=99. After m=97 (the second alt), we should
                            // become eligible for a reset attempt, and it should succeed.
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
        List<Integer> states = listener.states;
        int last = states.size() - 1;
        assertEquals(CircuitBreaker.CLOSED_STATE, (int)states.get(last));
        */
    }
}
