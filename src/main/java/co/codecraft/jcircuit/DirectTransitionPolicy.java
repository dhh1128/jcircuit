package co.codecraft.jcircuit;

/**
 * Does nothing in response to events, allowing transitions to be controlled solely by calls
 * to {@link CircuitBreaker#directTransition(int, boolean)}. Constructing a circuit
 * breaker with this policy puts it under manual control. This is generally not optimal, because
 * it means the logic that controls the circuit is not encapsulated cleanly inside a
 * policy object as this package's design prefers. However, it may be useful in cases
 * where a circuit breaker's state is governed purely by external conditions AND where
 * probing/monitoring those external conditions is not easy to wrap.
 */
public class DirectTransitionPolicy extends TransitionPolicy {
    @Override
    public void onGoodPulse() {
    }

    @Override
    public void onBadPulse(Throwable e) {
    }

    @Override
    public void onAltPulse() {
    }
}
