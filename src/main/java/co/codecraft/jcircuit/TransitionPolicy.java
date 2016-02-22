package co.codecraft.jcircuit;

/**
 * Embody and encapsulate the logic that reacts to signals to alter the state of a circuit breaker.
 */
public interface TransitionPolicy {

    /** Called by a circuit breaker if it detects that a reset might be possible. */
    boolean shouldReset(CircuitBreaker cb);

    /** We did normal work and succeeded. */
    public void onGoodPulse(CircuitBreaker cb);

    /**
     * We attempted normal work but failed.
     * @param e Used to distinguish different types of errors. May be null.
     */
    public void onBadPulse(CircuitBreaker cb, Throwable e);

    /** We short-circuited normal work and used fallback logic instead. */
    public void onAltPulse(CircuitBreaker cb);
}
