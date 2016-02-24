package co.codecraft.jcircuit;

/**
 * Embody and encapsulate the logic that reacts to signals to alter the state of a circuit breaker.
 *
 * The methods in this interface each accept a CircuitBreaker as an argument. This allows a single policy
 * object to service more than one CircuitBreaker instance. However, coding for a one-policy-to-many-breakers
 * design may introduce thorny performance-vs-correctness-for-concurrency issues; the normal expectation is
 * a one-to-one relationship.
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

    /**
     * @return true if our circuit breaker should incur some overhead to double-check correct behavior
     *         in this policy. This is very helpful during policy development. It can remain enabled in
     *         production code--the overhead is not prohibitive--but thoroughly polished policy classes
     *         may optimize by returning false.
     */
    public boolean shouldDebug();
}
