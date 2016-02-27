package co.codecraft.jcircuit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static co.codecraft.jcircuit.CircuitBreaker.*;

public class CircuitBreakerTest {
/*
    @Rule
    public ErrorCollector collector = new ErrorCollector();

    public static final HistorySink dudPolicy = new HistorySink() {
        @Override
        public boolean shouldReset(CircuitBreaker cb) { return false; }
        @Override
        public void onGoodPulse(CircuitBreaker cb) { }
        @Override
        public void onBadPulse(CircuitBreaker cb, Throwable e) { }
        @Override
        public void onAltPulse(CircuitBreaker cb) { }
        @Override
        public boolean shouldDebug() { return true; }
    };

    public static class StateCaptureListener implements Listener {
        List<Integer> states = new ArrayList<Integer>();
        public void onCircuitBreakerTransition(int oldState, int newState) {
            System.out.printf("transition %d --> %d\n", oldState, newState);
            if (states.isEmpty()) {
                states.add(oldState);
            }
            states.add(newState);
        }
    };

    public static class LoggingListener implements Listener {
        List<Integer> states = new ArrayList<Integer>();
        public void onCircuitBreakerTransition(int oldState, int newState) {
            System.out.printf("%d --> %d\n", oldState, newState);
        }
    };

    @Test
    public void test_redundant_transition_returns_true() {
        CircuitBreaker cb = new CircuitBreaker(dudPolicy, new LoggingListener());
        int snapshot = cb.getStateSnapshot();
        assertTrue(cb.transition(snapshot, 1));
        // This redundant call should succeed, even if the snapshot is now outdated, because it is seeking the same
        // goal as what we've already achieved.
        assertTrue(cb.transition(snapshot, 1));
    }
*/
}
