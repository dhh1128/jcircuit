package co.codecraft.jcircuit;

import static co.codecraft.jcircuit.Circuit.*;
import org.junit.Test;

import java.util.Random;

public class CircuitBreakerTest {

    @Test(expected=IllegalArgumentException.class)
    public void policy_may_not_be_null() {
        new CircuitBreaker(null, new CapturingListener());
    }

    @Test
    public void direct_transitions() {
        CapturingListener listener = new CapturingListener();
        CircuitBreaker cb = new CircuitBreaker(new DirectTransitionPolicy(), listener);
        // Generate a bunch of random events. Which event we generate shouldn't matter
        Random rand = new Random();
        for (int i = 0; i < 1000; ++i) {
            switch (i) {
                case 100: cb.directTransition(OPEN, false); break;
                case 200: cb.directTransition(CLOSED, false); break; // should be rejected because we didn't reset first
                case 300: cb.directTransition(OPEN, false); break; // redundant
                case 400: cb.directTransition(RESETTING, false); break;
                case 500: cb.directTransition(FAILED, false); break;
                case 600: cb.directTransition(CLOSED, true); break;
                case 700: cb.directTransition(FAILED, true); break;
                case 800: cb.directTransition(RESETTING, false); break;
                case 900: cb.directTransition(CLOSED, false); break;
                default:
                    switch (rand.nextInt(3)) {
                        case 0:
                            cb.onGoodPulse(); break;
                        case 1:
                            cb.onBadPulse(null); break;
                        default:
                            cb.onAltPulse(); break;
                    }
            }
        }
        listener.assertStates(CLOSED, OPEN, RESETTING, FAILED, CLOSED, FAILED, RESETTING, CLOSED);
    }
}
