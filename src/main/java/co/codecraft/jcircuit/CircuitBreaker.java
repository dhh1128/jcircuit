package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encapsulates the circuit breaker design pattern described by Michael Nygard in Release It! See
 * http://codecraft.co/2013/01/11/dont-forget-the-circuit-breakers/ for a discussion.
 *
 * Circuit breakers can be very useful as a way to manage runaway failures. The last thing you want, when
 * your code is battling temporary outages in a subsystem, is to create new problems by flooding logs or
 * bogging down elsewhere with expensive retries.
 *
 * Circuit breakers protect a fallible unit of work (a chunk of code) that will be called repeatedly.
 * This work is attempted whenever the circuit breaker is "closed" (see {@link #CLOSED_STATE}. In order
 * for the circuit breaker to be useful, there must be an alternate code path that is safer or less taxing,
 * and calling code must take this alternate path when the circuit is "open" (see {@link #OPEN_STATE}. The
 * obvious alternate/open code path for a circuit breaker is to do nothing, but more elaborate alternate
 * logic is conceivable.
 *
 * A single test of the circuit breaker, followed by the execution of either the normal or the alternate
 * codepath (per circuit breaker state), is called a "pulse."
 *
 * A circuit breaker may be "reset", meaning that it has been open, and we now want to close it again to
 * resume normal operations. Resetting (see {@link #RESETTING_STATE} can trigger automatically (e.g., after
 * a time delay or a number of pulses). If a circuit breaker has "failed" (see {@link #FAILED_STATE}, resets
 * are only manual. Whether automatic or manual, resets do not necessarily succeed.
 *
 * <pre>
 * State Machine
 *    closed --good--> closed
 *    closed --bad--> open
 *    open --alt--> (eventually) resetting
 *    resetting --good--> closed
 *    resetting --bad--> open
 *    resetting --enough bad--> (eventually, possibly) failed
 *    failed --manual reset request--> resetting
 * </pre>
 *
 * The state machines and core behaviors of all circuit breakers are identical. However, how they make the
 * decision to transition from one state to another varies. Some circuit breakers might trip after certain
 * numbers or types of errors; others might look at a failure rate relative to other metrics, or the
 * availability or scarcity of key resources, or at a combination of the above. Also, the criteria for
 * a successful reset may vary. For this reason, each circuit breaker is paired with a separate policy
 * object to specify thresholds and retry logic. The complexity is encapsulated in the policy, not in the
 * code that uses the technique.
 *
 * Because the changes in circuit breaker state may need to generate side effects beyond the scope of the
 * protected code, a {@link Listener} interface is provided to receive notifications.
 *
 * In single-threaded code, circuit breakers are easy to implement. They have a straightforward interface
 * interface and negligible cost. In multithreaded code, their design is trickier. A simplistic approach
 * might use mutexes--but in doing so, it would become fatally inefficient. The circuit breaker would be
 * a choke point for any parallelism in the system, as all paths of execution waited on centralized record-
 * keeping. And since the value of a robust circuit breaker is likely to be greatest for code where
 * concurrency is at a premium, this failure would be doubly unfortunate.
 *
 * Relief from this design dilemma comes when you realize that, by their very nature, circuit breakers are
 * not going to change state often. They are inherently oriented toward a read-frequently-write-seldom
 * model. This isn't just a matter of pure quantity; any individual write to circuit breaker state is
 * almost guaranteed to be separated from subsequent writes by a meaningful delay. The stickiness of circuit
 * breaker state is part of their usefulness. Write contention will be very low.
 *
 * Thus, this implementation uses an atomic to manage state, and should be lightweight and robust in the
 * face of the most demanding concurrency. Policy implementations should be wise about introducing state
 * that deviates from that achievement.
 *
 * Typical usage scenario
 *
 * 1. Encapsulate (in separate functions or code blocks) the functionality that you want to do when the
 *    system is normal (healthy) and when it is not.
 * 2. Create a circuit breaker to manage workflow.
 * 3. Write a block of code like this:
 *
 * <pre>
 *    // Always test state at the top of each pulse. Circuit breakers optimize this test intelligently,
 *    // especially for the common case when the circuit is closed. Note that we are NOT asking whether
 *    // the state of the circuit breaker is CLOSED; we might want to try normal if we are RESETTING
 *    // as well...
 *    if (myCircuitBreaker.shouldTryNormalPath()) {
 *        try {
 *            doNormalStuff();
 *            // Report good results so policy can update as needed (e.g., to complete a reset).
 *            myCircuitBreaker.onGoodPulse();
 *        } catch (Throwable e) {
 *            // Report bad results so policy can update as needed.
 *            myCircuitBreaker.onBadPulse(e);
 *        }
 *    } else {
 *        // optional; doing nothing may be what you want
 *        doSaferOrCheaperAlternateStuff();
 *        // Report alternate workflow so policy can update as needed (e.g., to schedule a reset).
 *        myCircuitBreaker.onAltPulse();
 *    }
 * </pre>
 *
 */
public class CircuitBreaker {

    /**
     * Encapsulate any state/knowledge/rules that will be used to make transition decisions.
     */
    public final TransitionPolicy policy;

    /**
     * Receive notifications when the state of the circuit breaker changes.
     */
    public final Listener listener;

    /**
     * Create a new CircuitBreaker that will transition per the specified policy.
     *
     * @param policy  May not be null.
     * @param listener  May be null.
     */
    public CircuitBreaker(TransitionPolicy policy, Listener listener) {
        if (policy == null) {
            throw new IllegalArgumentException("Policy cannot be null; circuit breaker would not know how to transition.");
        }
        this.policy = policy;
        this.listener = listener;
    }

    /*
     * Holds both the current state, and a monotonically increasing state *index* that tells us if
     * some other party changed the state after we last fetched it. Because we can update both of
     * these values as a single unit, in an atomic, we can handle concurrency with 100% accuracy,
     * without the overhead of mutexing. This design is very efficient, but it only works because
     * we expect circuit breakers to change state infrequently compared to how often they process
     * a "pulse." In other words, it's the right solution for a scenario where the number of reads
     * of shared state is much more than the number of writes, and where writes are almost always
     * spaced out in time.
     */
    private AtomicInteger stateSnapshot = new AtomicInteger(CLOSED_STATE);

    /**
     * @return an opaque integer that encapsulates the current condition of the circuit breaker, including
     * both public and private information. This integer may be masked with {@link #STATE_MASK} to produce
     * one of the *_STATE constants (see {@link #CLOSED_STATE}, {@link #OPEN_STATE}, {@link #RESETTING_STATE}
     * and {@link #FAILED_STATE}). The other bits are internal details with a meaning that may change.
     *
     * Note: This method is available for public auditing, but is mostly intended to be used by co-dependent
     * policy objects. It should not be used to route a pulse to normal or alternate workflow; contrast
     * {@link #shouldTryNormalPath()}.
     */
    public int getStateSnapshot() {
        return stateSnapshot.get();
    }

    /**
     * The circuit is closed. Normal workflow is active; the system is healthy. We expect circuit breakers
     * to be in this state the majority of the time.
     */
    public static final int CLOSED_STATE = 0;

    /**
     * The circuit is open (tripped by some kind of problem). Alternate workflow should be used.
     */
    public static final int OPEN_STATE = 1;

    /**
     * The circuit is attempting to transition from OPEN to CLOSED; in other words, the system has been
     * unhealthy, but we are now re-evaluating, hoping to see health again. Health criteria, and thus the
     * outcome of the reset, are governed by policy.
     */
    public static final int RESETTING_STATE = 2;

    /**
     * The circuit is in a permanent failure state. Automatic resets are no longer possible, but manual
     * resets can be attempted.
     */
    public static final int FAILED_STATE = 3;

    /**
     * Use this constant to mask the current state snapshot (see {@link #getStateSnapshot()} into a
     * *_STATE value for a simplified view of the circuit breaker's state machine.
     */
    public static final int STATE_MASK = 0x03;

    // Rather than bit twiddling to update the state index, just add this much to it every time. This
    // leaves the bottom two bits alone.
    private static final int INDEX_INCREMENTER = 4;

    // This mask grabs the top 30 bits of the state snapshot.
    private static final int INDEX_MASK = ~STATE_MASK;

    /**
     * Attempt to move our state machine to a new state. This will succeed and return true IFF:
     * A) the transition is a legal one (not something nonsensical like CLOSED-->RESETTING); and
     * B) the state has not changed since we last fetched it.
     */
    boolean transition(int oldSnapshot, int newState) {
        int oldState = oldSnapshot & STATE_MASK;
        // Validate legal transition.
        if (policy.shouldDebug()) {
            boolean valid;
            switch (newState) {
                case CLOSED_STATE:
                    valid = (oldState == RESETTING_STATE);
                    break;
                case OPEN_STATE:
                    valid = (oldState == CLOSED_STATE || oldState == RESETTING_STATE);
                    break;
                case RESETTING_STATE:
                    valid = (oldState == OPEN_STATE || oldState == FAILED_STATE);
                    break;
                case FAILED_STATE:
                    valid = (oldState == RESETTING_STATE);
                    break;
                default:
                    valid = false;
                    break;
            }
            if (!valid) {
                throw new IllegalArgumentException(String.format("Can't transition from state %d to %d.", oldState, newState));
            }
        }
        int newSnapshot = ((oldSnapshot & INDEX_MASK) + INDEX_INCREMENTER) | newState;

        // The common case: we were asked to update something valid, and nothing invalidated our starting assumptions,
        // so we succeed.
        if (stateSnapshot.compareAndSet(oldSnapshot, newSnapshot)) {
            if (listener != null) {
                listener.onCircuitBreakerTransition(oldState, newState);
            }
            return true;

        // The second most common case: the request was redundant because more than one thread asked for the same
        // transition.
        } else if ((stateSnapshot.get() & STATE_MASK) == newState) {
            return true;
        }

        // Otherwise something changed during our analysis.
        return false;
    }

    /**
     * Tell the circuit breaker that we just did a unit of work (a "pulse") that succeeded. This information
     * may be used by the circuit breaker's {@link #policy} to update state.
     */
    public void onGoodPulse() {
        policy.onGoodPulse(this);
    }

    /**
     * Tell the circuit breaker that we just did a unit of work (a "pulse") that failed. This information
     * may be used by the circuit breaker's {@link #policy} to update state.
     *
     * @param e  What went wrong. May be null if the exception is not available. Policy implementations
     *           may use this param to make more intelligent transition choices (e.g., to distinguish
     *           between a temporary and a permanent error).
     */
    public void onBadPulse(Throwable e) {
        policy.onBadPulse(this, e);
    }

    /**
     * Tell the circuit breaker that we just did an alternate unit of work (a "pulse") because the circuit
     * was open. This information may be used by the circuit breaker's {@link #policy} to update state.
     */
    public void onAltPulse() {
        policy.onAltPulse(this);
    }

    /**
     * Public users of the circuit breaker should call this method to decide whether to attempt normal or
     * alternate/fallback work.
     */
    public boolean shouldTryNormalPath() {
        // We use an infinite loop here to support retry in the extremely rare case where the state
        // changes in the middle of our analysis.
        while (true) {

            // Capture the state when we began our analysis.
            int snapshot = stateSnapshot.get();
            switch (snapshot & STATE_MASK) {

                // If there's an obvious answer, return it. Make the common cases fast.
                case CLOSED_STATE:
                case RESETTING_STATE:
                    return true;
                case FAILED_STATE:
                    return false;

                // This is the complex case. If we're currently open, we need to test for automatic resetting.
                case OPEN_STATE:
                    // Policy needs to implement this method efficiently!
                    if (policy.shouldReset(this)) {

                        // Attempt to transition into resetting. This will fail if some other thread beat us to
                        // it, or if conditions changed such that we should no longer reset by the time we got
                        // to this line.
                        if (transition(snapshot, RESETTING_STATE)) {
                            return true;
                        }

                        // We failed to transition because the state changed since we fetched it. Restart
                        // the logic.
                        System.out.println("retry in shouldTryNormalPath");
                        continue;
                    }
                    return false;

                default:
                    throw new AssertionError(String.format("snapshot is not in a valid state (%d)", snapshot));
            }
        }
    }

    /**
     * Receive notifications whenever the circuit breaker changes state (e.g., from {@link #CLOSED_STATE} to
     * {@link #OPEN_STATE}. Processing these events should be very fast and light, to avoid bogging down the
     * circuit breaker.
     */
    public interface Listener {
        void onCircuitBreakerTransition(int oldState, int newState);
    }
}
