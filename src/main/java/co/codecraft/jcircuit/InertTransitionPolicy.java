package co.codecraft.jcircuit;

/**
 * Does nothing in response to events, allowing transitions to be controlled only by calls
 * to the circuit breaker's direct* methods.
 */
public class InertTransitionPolicy extends TransitionPolicy {
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
