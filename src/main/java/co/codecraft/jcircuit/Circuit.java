package co.codecraft.jcircuit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proxies a code path, action, or feature that needs to be gracefully degraded or disabled
 * in some circumstances. Provides a simple state machine and the ability to notify a listener
 * about transitions in that state machine.
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
 * This class is threadsafe. It is efficient, even in the face of aggressive concurrency.
 */
public class Circuit {

    /**
     * Setting this to true causes {@link #transition(int, int)} to execute a handful of conditionals
     * to test that the start and end state of a transition actually match the rules of our state
     * machine. This may be helpful while developing new {@link CircuitBreaker} implementations, and
     * is not overly expensive, but it is generally unnecessary in production.
     */
    public boolean shouldValidate = false;

    /**
     * Receive notifications when the state of the circuit breaker changes.
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
     * one of the * constants (see {@link #CLOSED}, {@link #OPEN}, {@link #RESETTING}
     * and {@link #FAILED}). The other bits are internal details with a meaning that may change.
     *
     * Note: This method is available for public auditing, but is mostly intended to be used by co-dependent
     * objects. It should not be used to route a pulse to normal or alternate workflow.
     */
    public int getStateSnapshot() {
        return stateSnapshot.get();
    }

    /**
     * The circuit is closed. Normal workflow is active; the system is healthy. We expect circuit breakers
     * to be in this state the majority of the time.
     */
    public static final int CLOSED = 0;

    /**
     * The circuit is open (tripped by some kind of problem). Alternate workflow should be used.
     */
    public static final int OPEN = 1;

    /**
     * The circuit is attempting to transition from OPEN to CLOSED; in other words, the system has been
     * unhealthy, but we are now re-evaluating, hoping to see health again. Health criteria, and thus the
     * outcome of the reset, are governed by transitionPolicy.
     */
    public static final int RESETTING = 2;

    /**
     * The circuit is in a permanent failure state. Automatic resets are no longer possible, but manual
     * resets can be attempted.
     */
    public static final int FAILED = 3;

    /**
     * Use this constant to mask the current state snapshot (see {@link #getStateSnapshot()} into a
     * * value for a simplified view of the circuit breaker's state machine.
     */
    public static final int STATE_MASK = 0x03;

    // Rather than bit twiddling to update the state index, just add this much to it every time. This
    // leaves the bottom two bits alone.
    private static final int INDEX_INCREMENTER = 4;

    // This mask grabs the top 30 bits of the state snapshot.
    private static final int INDEX_MASK = ~STATE_MASK;

    /**
     * Attempt to move our state machine to a new state. This will succeed and return true if:
     * <ul>
     *     <li>The state machine is already in the requested state, making the transition redundant</li>
     *     <li>OR the state has not changed since we last fetched it with {@link #getStateSnapshot()}</li>
     * </ul>
     * If {@link #shouldValidate} is true, then the correctness of old and new state are checked with
     * each call, and an {@link IllegalArgumentException} is thrown if anomalies are detected. (This
     * correctness is not evaluated against the actual state of the circuit, but rather against the
     * asserted oldSnapshot. In other words, it can only generate an exception if the caller is
     * coded wrong, not if the caller is unaware of the current state of the circuit.) This may
     * be helpful while developing a new {@link CircuitBreaker}.
     */
    public boolean transition(int oldSnapshot, int newState) {
        int oldState = oldSnapshot & STATE_MASK;
        // Validate legal transition.
        if (shouldValidate) {
            boolean valid;
            switch (newState) {
                case CLOSED:
                    valid = (oldState == RESETTING);
                    break;
                case OPEN:
                    valid = (oldState == CLOSED || oldState == RESETTING);
                    break;
                case RESETTING:
                    valid = (oldState == OPEN || oldState == FAILED);
                    break;
                case FAILED:
                    valid = (oldState == RESETTING);
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

    /**
     * @return true if the old and new states are valid for our defined state machine.
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
     * Receive notifications whenever the circuit breaker changes state (e.g., from {@link #CLOSED} to
     * {@link #OPEN}. Processing these events should be very fast and light, to avoid bogging down the
     * circuit breaker.
     */
    public interface Listener {
        void onCircuitTransition(Circuit circuit, int oldState, int newState);
    }
}
