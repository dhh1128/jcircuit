package co.codecraft.jcircuit;

import java.lang.ref.WeakReference;

/**
 * Used by a {@link CircuitBreaker} to force its {@link Circuit} to transition.
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

    /** Notify transitionPolicy that we did normal work and succeeded. */
    abstract public void onGoodPulse();

    /**
     * Notify transitionPolicy that we attempted normal work but failed.
     * @param e Used to distinguish different types of errors. May be null.
     */
    abstract public void onBadPulse(Throwable e);

    /**
     * Notify transitionPolicy that we attempted normal work but failed.
     */
    final public void onBadPulse() { onBadPulse(null); }

    /** Notify transitionPolicy that we short-circuited normal work and used fallback logic instead. */
    abstract public void onAltPulse();
}
