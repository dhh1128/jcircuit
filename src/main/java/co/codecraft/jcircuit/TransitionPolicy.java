package co.codecraft.jcircuit;

/**
 * Used by a {@link CircuitBreaker} to force its {@link Circuit} to transition.
 */
public interface TransitionPolicy {

    /** Notify transitionPolicy that we did normal work and succeeded. */
    public void onGoodPulse(CircuitBreaker cb);

    /**
     * Notify transitionPolicy that we attempted normal work but failed.
     * @param e Used to distinguish different types of errors. May be null.
     */
    public void onBadPulse(CircuitBreaker cb, Throwable e);

    /** Notify transitionPolicy that we short-circuited normal work and used fallback logic instead. */
    public void onAltPulse(CircuitBreaker cb);
}
