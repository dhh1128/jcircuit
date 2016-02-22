package co.codecraft.jcircuit;

import static co.codecraft.jcircuit.CircuitBreakerX.State.*;

/**
 * Implements the CircuitBreakerX pattern for simple scenarios where code is single-threaded.
 */
public class SynchronousCircuitBreaker extends CircuitBreakerX {

    private State state = CLOSED;

    public SynchronousCircuitBreaker(TransitionPolicy p) {
        super(p);
    }

    protected int getCurrentStateToken() {
        return state.ordinal();
    }

    protected boolean transition(int fromToken, int toToken) {
        if (state.ordinal() == fromToken) {
            state = state.values()[toToken];
            return true;
        }
        return false;
    }

    public boolean shouldTryNormalPath() {
        switch (state) {
            case CLOSED:
            case RESETTING:
                return true;
            case OPEN:
                if (policy.shouldReset(this)) {
                    state = State.RESETTING;
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

}
