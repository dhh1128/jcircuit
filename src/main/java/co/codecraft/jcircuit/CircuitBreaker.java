package co.codecraft.jcircuit;

/**
 * Implements the circuit-breaker design pattern described by Michael Nygard in Release It! See
 * http://codecraft.co/2013/01/11/dont-forget-the-circuit-breakers/ for a discussion.
 *
 * Circuit breakers can be very useful as a way to manage runaway failures. The last thing you want,
 * when your code is battling temporary outages in a subsystem, is to create new problems by flooding
 * logs or bogging down with retries.
 *
 * The fundamental behavior of all circuit breakers is similar: they have a simple state machine, and
 * they route workflow (program logic) by which state is active. How they make the decision to transition
 * from one state to another is highly variable. Some circuit breakers might trip after a certain number
 * of errors; others might look at a failure rate relative to other metrics, or the availability or
 * scarcity of key resources, or at a combination of the above. For this reason, this design separates
 * core logic from policy about transitions. Using circuit breakers will usually require you to define
 * your own policy, but not new derivations of CircuitBreaker.
 *
 * In single-threaded code, circuit breakers are easy to implement. They have a straightforward
 * interface and no significant cost. In multithreaded code, their design is a bit trickier; we don't
 * want the very mechanism that's supposed to enhance robustness to become a new bottleneck. Be sure
 * you understand the paradigm for such uses cases.
 *
 * Typical usage scenario
 *
 * 1. Encapsulate the functionality that you want to do when the system is normal (healthy) and when
 *    it is not, in separate functions.
 * 2. Create a circuit breaker to manage workflow.
 * 3. Write a block of code like this:
 *
 *    if (myCircuitBreaker.shouldTryNormal()) {
 *        try {
 *            doNormalStuff();
 *            myCircuitBreaker.signal(HEALTH);
 *        } catch (Throwable e) {
 *            myCircuitBreaker.signal(TROUBLE, e);
 *        }
 *    }
 *
 *
 */
abstract public class CircuitBreaker {

    /**
     * Describe the current condition of the workflows that the circuit breaker is protecting.
     */
    public enum State {

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

    /**
     * Signal what kind of outcomes we're seeing from our workflows.
     */
    public enum Health {
        HEALTHY,
        TROUBLED,

    }

    /**
     * Report current state. This method is not used
     */
    abstract public State getState();


}
