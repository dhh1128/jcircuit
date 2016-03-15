package co.codecraft.jcircuit;

import java.lang.ref.WeakReference;

/**
 * <p>Used by a {@link CircuitBreaker} to control the manner and timing of transitions in its {@link Circuit}.
 * A policy does this by observing the flow of events (and possibly the condition of external resources like the
 * clock, the file system, the network, etc), and calling {@link Circuit#transition(int, int)} as appropriate.
 * </p>
 *
 * <p>Methods of this class are called by the {@link CircuitBreaker} that owns the policy--public callers never
 * use them directly.</p>
 *
 * <p>Each CircuitBreaker has its own unique TransitionPolicy instance; the two objects are bound together
 * during the CircuitBreaker's constructor. If multiple instances of the same TransitionPolicy class need to
 * share state, then the TransitionPolicy can be written to support that. However, remember that if a
 * TransitionPolicy instance has to update shared state used by more than one Circuit, it will likely need a
 * mutex to do so safely. This creates a potential bottleneck that could be problematic.
 * </p>
 */
abstract public class TransitionPolicy {

    protected WeakReference<CircuitBreaker> cb;
    protected Circuit circuit;

    /**
     * Called automatically by a CircuitBreaker in its constructor. Throws an {@link IllegalStateException} elsewhere.
     */
    void bindTo(CircuitBreaker cb) {
        this.cb = new WeakReference<CircuitBreaker>(cb);
        this.circuit = cb.circuit;
    }

    /** Notify the policy that we did normal work and succeeded. */
    abstract public void onGoodPulse();

    /**
     * Notify the policy that we attempted normal work but failed.
     * @param  e Used to distinguish different types of errors. May be null.
     */
    abstract public void onBadPulse(Throwable e);

    /**
     * Notify the policy that we attempted normal work but failed. Calls {@link #onBadPulse(Throwable)} with
     * <code>e</code> = <code>null</code>.
     */
    final public void onBadPulse() { onBadPulse(null); }

    /** Notify the policy that we short-circuited normal work and used fallback logic instead. */
    abstract public void onAltPulse();

    /**
     * <p>A CircuitBreaker calls this method as soon as its own {@link CircuitBreaker#directTransition(int, boolean)
     * directTransition()} method is invoked. This notifies its policy that an external entity wants to change the
     * state of the circuit breaker independent of policy logic/state. The policy has a chance to reject the change,
     * or to react to it gracefully.</p>
     *
     * @param desiredState  The state that the caller wants to activate.
     * @param force  Whether the caller will demand the state regardless of state machine rules.
     * @return true if the change should be allowed, or false if it must be rejected.
     */
    public boolean beforeDirectTransition(int desiredState, boolean force) { return true; }

    /**
     * <p>A CircuitBreaker calls this method after its own {@link CircuitBreaker#directTransition(int, boolean)
     * directTransition()} completes. This notifies its policy that an external entity just changed the
     * state of the circuit breaker independent of policy logic/state, allowing it to react gracefully.</p>
     *
     * @param desiredState  The state that the caller wants to activate.
     * @param force  Whether the caller will demand the state regardless of state machine rules.
     */
    public void afterDirectTransition(int desiredState, boolean force) { }
}
