package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>A Circuit proxies a code path, action, or feature that needs to be gracefully degraded or disabled
 * in some circumstances. It provides a simple state machine and the ability to notify a listener
 * about transitions. Circuits are never created directly; they are managed by a {@link CircuitBreaker}
 * that is the true public interface for jcircuit features. Study CircuitBreaker documentation for
 * an overview of key concepts.</p>
 *
 * <h3><a name="statemachine">State Machine</a></h3>
 * <pre>
 *    {@link #CLOSED} --good pulse--&gt; {@link #CLOSED}
 *    {@link #CLOSED} --bad pulses (# or ratio per {@link TransitionPolicy policy})--&gt; {@link #OPEN}
 *    {@link #OPEN} --alt pulses--&gt; (eventually, per {@link TransitionPolicy policy}) {@link #RESETTING}
 *    {@link #RESETTING} --good pulses--&gt; {@link #CLOSED}
 *    {@link #RESETTING} --bad pulses--&gt; {@link #OPEN}
 *    {@link #RESETTING} --enough bad--&gt; (eventually, if {@link TransitionPolicy policy} says so) {@link #FAILED}
 *    {@link #FAILED} --manual reset request--&gt; {@link #RESETTING}
 * </pre>
 * 
 * <p>Circuits are efficiently threadsafe, even in the face of aggressive concurrency. Each uses
 * a single atomic long to track state, rather than mutexes.</p>
 */
public class Circuit {

    /**
     * Setting this to true causes {@link #transition(int, int)} to execute a handful of conditionals
     * to test that the start and end state of a transition actually match the rules of our state
     * machine. This may be helpful while developing new {@link CircuitBreaker} implementations, and
     * it is not overly expensive, but it is generally unnecessary in production.
     */
    public boolean shouldValidate = false;

    /**
     * Receive notifications when the state of the circuit changes.
     */
    public final Listener listener;

    /**
     * Create a new Circuit object that proxies the state of a code path or action/feature. 
     *
     * @param listener  May be null.
     */
    public Circuit(Listener listener) {
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
    private AtomicInteger stateSnapshot = new AtomicInteger(CLOSED);

    /**
     * @return an opaque integer that encapsulates the current condition of the circuit breaker, including
     * both public and private information. This integer may be masked with {@link #STATE_MASK} to produce
     * one of the state constants ({@link #CLOSED}, {@link #OPEN}, {@link #RESETTING}
     * and {@link #FAILED}). The other bits are internal details with a meaning that may change.
     *
     * <p>Note: This method is available for public auditing, but is mostly intended to be used by co-dependent
     * objects. It should not be used to route a pulse to normal or alternate workflow.</p>
     */
    public int getStateSnapshot() {
        return stateSnapshot.get();
    }

    /**
     * The circuit is closed. Normal workflow is active; the system is healthy. We expect circuit breakers
     * to be in this state the majority of the time. (This constant is enum-like, but is defined as an int
     * to allow efficient bitmasking.)
     */
    public static final int CLOSED = 0;

    /**
     * The circuit is open (tripped by some kind of problem). Alternate workflow should be used.
     * (This constant is enum-like, but is defined as an int to allow bitmasking.)
     */
    public static final int OPEN = 1;

    /**
     * The circuit is attempting to transition from OPEN to CLOSED; in other words, the system has been
     * unhealthy, but we are now re-evaluating, hoping to see health again. Health criteria, and thus the
     * outcome of the reset, are governed by a {@link TransitionPolicy}. (This constant is enum-like, but is defined
     * as an into to allow bitmasking.)
     */
    public static final int RESETTING = 2;

    /**
     * The circuit is in a permanent failure state. Automatic resets are no longer possible, but manual
     * resets can be attempted. (This constant is enum-like, but is defined as an int to allow bitmasking.)
     */
    public static final int FAILED = 3;

    /**
     * Use this constant to mask the current state snapshot (see {@link #getStateSnapshot()} into a
     * value like {@link #OPEN} or {@link #CLOSED} for a simplified view of the circuit breaker's
     * state machine.
     */
    public static final int STATE_MASK = 0x03;

    // Rather than bit twiddling to update the state index, just add this much to it every time. This
    // leaves the bottom two bits alone.
    private static final int INDEX_INCREMENTER = 4;


    // This mask grabs the top 30 bits of the state snapshot.
    private static final int INDEX_MASK = ~STATE_MASK;

    /**
     * <p>Attempt to move our state machine to a new state. This method should be called by {@link TransitionPolicy}
     * objects, not the general public. (For direct manipulation of a circuit breaker, see {@link
     * CircuitBreaker#directTransition(int, boolean)} instead.)</p>
     *
     * <p>The request to transition will succeed if:</p>
     * <ul>
     *     <li>The state machine is already in the requested state, making the transition redundant</li>
     *     <li><strong>OR</strong> the state has not changed since we last fetched it with {@link #getStateSnapshot()}</li>
     * </ul>
     *
     * <p>If {@link #shouldValidate} is true, then the correctness of old and new state are checked with
     * each call, and an {@link IllegalArgumentException} is thrown if anomalies are detected. (This
     * correctness is not evaluated against the actual state of the circuit, but rather against the
     * asserted oldSnapshot. In other words, it can only generate an exception if the caller is
     * coded wrong, not if the caller is unaware of the current state of the circuit.) This may
     * be helpful while developing a new {@link CircuitBreaker}.</p>
     *
     * @param oldSnapshot  Asserts that the circuit is currently in the state described by oldSnapshot.
     *                     This value is returned by {@link #getStateSnapshot()}; it is more than
     *                     one of the state constants ({@link #OPEN}, {@link #CLOSED}, etc).
     * @param newState  The new state that's desired. See {@link #OPEN}, {@link #CLOSED}, etc.
     * @return  true if the circuit is in the desired state when the function completes, false if not.
     */
    public boolean transition(int oldSnapshot, int newState) {
        int oldState = oldSnapshot & STATE_MASK;
        // Validate legal transition.
        if (shouldValidate) {
            if (!isValidTransition(oldState, newState)) {
                throw new IllegalArgumentException(String.format("Can't transition from state %d to %d.", oldState, newState));
            }
        }
        int newSnapshot = ((oldSnapshot & INDEX_MASK) + INDEX_INCREMENTER) | newState;

        // The common case: we were asked to update something valid, and nothing invalidated our starting assumptions,
        // so we succeeded.
        if (stateSnapshot.compareAndSet(oldSnapshot, newSnapshot)) {
            if (listener != null) {
                // Don't report (non-)events where state wasn't updated.
                if (oldState != newState) {
                    listener.onCircuitTransition(this, oldState, newState);
                }
            }
            return true;

        // The second most common case: the atomic couldn't be updated, but it doesn't matter, because the
        // request was redundant (state was already correct). This happens when multiple threads ask for
        // the same transition.
        } else if ((stateSnapshot.get() & STATE_MASK) == newState) {
            return true;
        }

        // Otherwise our caller asked for something we can't honor. Typically this is because concurrency
        // invalidated the caller's view of what our current state was.
        return false;
    }

    // Only used for CircuitBreaker.directTransition(force=true).
    void unsafeTransition(int newState) {
        while (true) {
            int oldSnapshot = stateSnapshot.get();
            int oldState = oldSnapshot & STATE_MASK;
            if (oldState == newState) {
                return;
            }
            int newSnapshot = ((oldSnapshot & INDEX_MASK) + INDEX_INCREMENTER) | newState;

            if (stateSnapshot.compareAndSet(oldSnapshot, newSnapshot)) {
                if (listener != null) {
                    listener.onCircuitTransition(this, oldState, newState);
                }
                return;
            }
        }
    }

    /**
     * @return true if the old and new states are valid for our defined state machine. Transitioning from
     *     {@link #CLOSED} to {@link #OPEN} is valid; transitioning from {@link #OPEN} to {@link #CLOSED} is
     *     not because the circuit must pass through {@link #RESETTING} first.
     *
     * @param oldStateOrSnapshot  The state (or the complex snapshot that includes state) from which the
     *                            transition would proceed.
     * @param newStateOrSnapshot  The state (or the complex snapshot that includes state) into which the
     *                            transition would resolve.
     */
    public static boolean isValidTransition(int oldStateOrSnapshot, int newStateOrSnapshot) {
        int newState = newStateOrSnapshot & STATE_MASK;
        switch (oldStateOrSnapshot & STATE_MASK) {
            case CLOSED:
                return newState == OPEN || newState == CLOSED;
            case OPEN:
                return newState == RESETTING || newState == OPEN;
            case FAILED:
                return newState == RESETTING || newState == FAILED;
            case Circuit.RESETTING:
                return newState == CLOSED || newState == FAILED || newState == OPEN || newState == RESETTING;
            default:
                return false;
        }
    }

    /**
     * Receive notifications just after the circuit changes state (e.g., from {@link #CLOSED CLOSED} to
     * {@link #OPEN OPEN}). Processing these events should be very fast and light, to avoid bogging down the
     * circuit breaker.
     */
    public interface Listener {
        /**
         * @param circuit  The {@link Circuit} that has changed.
         * @param oldState  The previous state. This will be one of the following constants: {@link #CLOSED CLOSED},
         *                  {@link #OPEN OPEN}, {@link #RESETTING RESETTING}, or {@link #FAILED FAILED}.
         * @param newState  The new state. This will be one of the following constants: {@link #CLOSED CLOSED},
         *                  {@link #OPEN OPEN}, {@link #RESETTING RESETTING}, or {@link #FAILED FAILED}.
         */
        void onCircuitTransition(Circuit circuit, int oldState, int newState);
    }

    /**
     * Converts a state constant such as {@link #CLOSED} to a string such as "CLOSED".
     * @param state  A state constant such as {@link #OPEN}, or a complex snapshot that includes state bits. If the
     *               state is unrecognized, an {@link IllegalArgumentException} is thrown.
     * @return  A string that describes the state.
     */
    public static String stateToString(int state) {
        switch (state & STATE_MASK) {
            case CLOSED: return "CLOSED";
            case OPEN: return "OPEN";
            case RESETTING: return "RESETTING";
            case FAILED: return "FAILED";
            default: throw new IllegalArgumentException(String.format("Unrecognized state %d.", state));
        }
    }

    /**
     * Converts a string such as "CLOSED" to a state constant such as {@link #CLOSED}.
     * @param name  A string such as "CLOSED" or "OPEN". Case doesn't matter. If the string is unrecognized,
     *              an {@link IllegalArgumentException} is thrown.
     * @return  A a state constant such as {@link #CLOSED} or {@link #OPEN}.
     */
    public static int stringToState(String name) {
        if (name == null) {
            throw new IllegalArgumentException("State name cannot be null.");
        }
        int n = name.compareToIgnoreCase("OPEN");
        if (n < 0) {
            n = name.compareToIgnoreCase("CLOSED");
            if (n == 0) {
                return CLOSED;
            } else if (n > 0 && name.compareToIgnoreCase("FAILED") == 0) {
                return FAILED;
            }
        } else {
            if (n > 0 && name.compareToIgnoreCase("RESETTING") == 0) {
                return RESETTING;
            } else {
                return OPEN;
            }
        }
        throw new IllegalArgumentException(String.format("Unrecognized state %s.", name));
    }

}
