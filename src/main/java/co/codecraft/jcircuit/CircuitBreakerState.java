package co.codecraft.jcircuit;


/**
 * Describe the current condition of the circuit breaker.
 */
public enum CircuitBreakerState {

    /** The circuit is closed. Normal workflow is active; the system is healthy. */
    CLOSED,

    /** The circuit is open (tripped by some kind of problem). Alternate workflow should be used. */
    OPEN,

    /**
     * The circuit is attempting to transition from OPEN to CLOSED; in other words, the system has been
     * unhealthy, but we are now re-evaluating, hoping to see health again. Health criteria, and thus the
     * outcome of the reset, are governed by policy.
     */
    RESETTING,

    /**
     * The circuit is in a permanent failure state. Automatic resets are no longer possible, but manual
     * resets can be attempted.
     */
    FAILED
}
