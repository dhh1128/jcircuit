package co.codecraft.jcircuit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static co.codecraft.jcircuit.Circuit.*;

/**
 * <p>Encapsulates the circuit breaker design pattern described by Michael Nygard in <em>Release It!</em> See
 * <a href="http://codecraft.co/2013/01/11/dont-forget-the-circuit-breakers">"Don't Forget the Circuit Breakers"</a>
 * for a discussion.</p>
 *
 * <p>Circuit breakers can be very useful as a way to manage runaway failures. The last thing you want, when
 * your code is battling temporary outages in a subsystem, is to create new problems by flooding logs or
 * bogging down elsewhere with expensive retries. With a circuit breaker, you can degrade (and later
 * recover) gracefully and automatically.</p>
 *
 * <h3>Key Concepts</h3>
 *
 * <p>Circuit breakers protect a fallible unit of work (a chunk of code) that will be called repeatedly. This
 * is called a {@link Circuit "circuit"}. A circuit <em>breaker</em> encapsulates the management wrapping
 * its circuit.</p>
 *
 * <p>Normal work is attempted whenever the circuit is {@link Circuit#CLOSED "closed"}. In order
 * for the circuit breaker to be useful, there must be an alternate code path that is safer or less taxing,
 * and calling code must take this alternate path when the circuit is {@link Circuit#OPEN "open"}. The
 * obvious alternate/open code path is to do nothing, but more elaborate alternate logic is conceivable.</p>
 *
 * <p>A single test of the circuit breaker, followed by the execution of either the normal or the alternate
 * codepath, is called a "pulse."</p>
 *
 * <p>A circuit breaker may be "reset", meaning that its circuit has been open, and we now want to close it again to
 * resume normal operations. {@link Circuit#RESETTING Resetting} can trigger automatically (e.g., after
 * a time delay or a number of pulses). If a circuit has {@link Circuit#FAILED failed}, resets
 * are only manual (via the {@link #directTransition(int, boolean) directTransition()} method). Whether automatic
 * or manual, resets do not necessarily succeed.</p>
 *
 * <p>Circuit breakers normally have a lifespan that corresponds to the code path they protect; create them as statics
 * when a class is loaded, rather than as instance variables. There may be a few use cases
 * that violate this assumption, and that may be fine--but that is not the sweet spot for their design.</p>
 *
 * <p>The <a href="Circuit.html#statemachine">state machines</a> of all circuits are identical, as are thevalid core
 * behaviors of all circuit breakers. However, how circuit breakers decide to transition from one state to
 * another varies. Some circuit breakers might trip after certain numbers or types of errors; others might
 * look at a failure rate relative to other metrics, or the availability or scarcity of key resources,
 * or at a combination of the above. Also, the criteria for a successful reset may vary. For this reason,
 * each circuit breaker is associated with a separate {@link TransitionPolicy} object to define thresholds
 * and retry logic. The policy, not the circuit breaker, embodies the main complexity involved in the
 * technique. As a consumer of this package, you will likely customize by writing a new TransitionPolicy.
 * Study the implementation of the samples, and their unit tests, to understand how.</p>
 *
 * <p>Because changes in circuit state may need to generate side effects beyond the scope of the
 * protected code, a {@link Listener} interface may be used to receive notifications.</p>
 *
 * <p>In single-threaded code, circuit breakers are easy to implement. They have a straightforward
 * interface and negligible cost. In multithreaded code, their design is trickier. A simplistic approach
 * might use mutexes--but in doing so, it would become fatally inefficient: the circuit breaker would be
 * a choke point for any parallelism in the system, as all paths of execution waited on centralized record-
 * keeping. And since the value of a robust circuit breaker is likely to be greatest for code where
 * concurrency is at a premium, this failure would be doubly unfortunate.</p>
 *
 * <p>Relief from this design dilemma comes when you realize that, by their very nature, circuit breakers are
 * not going to change state often. They are inherently oriented toward a read-frequently-write-seldom
 * model. This isn't just a matter of pure quantity; any individual write to circuit breaker state is
 * almost guaranteed to be separated from subsequent writes by a meaningful delay. The stickiness of circuit
 * breaker state is part of their usefulness. Write contention will be very low.</p>
 *
 * <p>Thus, this implementation uses an atomic to manage state, and should be lightweight and robust in the
 * face of the most demanding concurrency. Policy implementations should be wise about introducing state
 * that deviates from that achievement.</p>
 *
 * <h3>Typical usage scenario</h3>
 *
 * <ol>
 * <li>Encapsulate (in separate functions or code blocks) the functionality that you want to do when the
 *    system is normal (healthy) and when it is not.</li>
 * <li>Create a circuit breaker to manage workflow.</li>
 * <li>Write a block of code like this:</li>
 * </ol>
 *
 * <pre>
 *    // Always test state at the top of each pulse. Circuit breakers optimize this test intelligently,
 *    // especially for the common case when the circuit is closed. Note that we are NOT asking whether
 *    // the state of the circuit breaker is CLOSED; we might want to try normal if we are RESETTING
 *    // as well...
 *    if (myCircuitBreaker.{@link #shouldTryNormalPath()}) {
 *        try {
 *            doNormalStuff();
 *            // Report good results so transitionPolicy can update as needed (e.g., to complete a reset).
 *            myCircuitBreaker.{@link #onGoodPulse()};
 *        } catch (Throwable e) {
 *            // Report bad results so transitionPolicy can update as needed.
 *            myCircuitBreaker.{@link #onBadPulse(Throwable) onBadPulse(e)};
 *        }
 *    } else {
 *        // optional; doing nothing may be what you want
 *        doSaferOrCheaperAlternateStuff();
 *        // Report alternate workflow so transitionPolicy can update as needed (e.g., to schedule a reset).
 *        myCircuitBreaker.onAltPulse();
 *    }
 * </pre>
 */
public class CircuitBreaker {

    public final Circuit circuit;

    /**
     * Implements transition decisions for this circuit breaker.
     */
    public final TransitionPolicy transitionPolicy;

    /**
     * Provides a thread that can be used to monitor circuit breakers that do very light work
     * on a schedule. This can be used by policies that need to check something periodically.
     * It is important that tasks run by this service complete quickly (microseconds), to avoid
     * bogging down notifications needed by other parts of the system.
     */
    public static final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    /**
     * Create a new CircuitBreaker that transitions per the specified policy.
     *
     * @param policy  May not be null.
     * @param listener  May be null.
     */
    public CircuitBreaker(TransitionPolicy policy, Circuit.Listener listener) {
        if (policy == null) {
            throw new IllegalArgumentException("Policy cannot be null; circuit breaker would not know how to transition.");
        }
        this.transitionPolicy = policy;
        this.circuit = new Circuit(listener);
        policy.bindTo(this);
    }

    /**
     * Tell the circuit breaker that we just did a unit of work (a "pulse") that succeeded. This information
     * may be used by the circuit breaker's {@link #transitionPolicy} to update state.
     */
    public void onGoodPulse() {
        transitionPolicy.onGoodPulse();
    }

    /**
     * Tell the circuit breaker that we just did a unit of work (a "pulse") that failed. This information
     * may be used by the circuit breaker's {@link #transitionPolicy} to update state.
     *
     * @param e  What went wrong. May be null if the exception is not available. Policy implementations
     *           may use this param to make more intelligent transition choices (e.g., to distinguish
     *           between a temporary and a permanent error).
     */
    public void onBadPulse(Throwable e) {
        transitionPolicy.onBadPulse(e);
    }

    /**
     * Tell the circuit breaker that we just did an alternate unit of work (a "pulse") because the circuit
     * was open. This information may be used by the circuit breaker's {@link #transitionPolicy} to update state.
     */
    public void onAltPulse() {
        transitionPolicy.onAltPulse();
    }

    /**
     * Public users of the circuit breaker should call this method to decide whether to attempt normal or
     * alternate/fallback work.
     *
     * @return true if the caller should attempt normal work.
     */
    public boolean shouldTryNormalPath() {
        int snapshot = circuit.getStateSnapshot();
        switch (snapshot & STATE_MASK) {
            case CLOSED:
            case RESETTING:
                return true;
            default:
                return false;
        }
    }

    /**
     * <p>Directly transition the {@link Circuit} managed by this CircuitBreaker to a specific state,
     * without waiting for a policy to trigger the transition. This can be used to do manual resets
     * out of a {@link Circuit#FAILED FAILED} state (<code>desiredState</code> = {@link Circuit#RESETTING RESETTING}),
     * or for more draconian manipulations.</p>
     *
     * <p>A manual {@link Circuit#FAILED FAILED} --&gt; {@link Circuit#RESETTING RESETTING} transition through
     * this method does not close the circuit; it simply begins the retesting of circuit health that will eventually
     * close or open it again. If you want to directly transition {@link Circuit#FAILED FAILED} all the way to
     * {@link Circuit#CLOSED CLOSED} (ignoring the policy's criteria for a successful reset), you have to make two
     * calls ({@link Circuit#FAILED FAILED} --&gt; {@link Circuit#RESETTING RESETTING}, then Circuit#RESETTING
     * RESETTING} --&gt; {@link Circuit#CLOSED CLOSED}. Since other code might alter state in between the two calls,
     * there is no guarantee of this succeeding. You can use the <code>force</code> parameter if you need a
     * deterministic outcome.</p>
     *
     * <p>Besides the manual reset workflow, the other common use case for this method is in conjunction with a
     * {@link DirectTransitionPolicy}, where policy is essentially inert.</p>
     *
     * @param force  If true, ignore <a href="Circuit.html#statemachine">state machines rules</a> and set the state
     *               regardless of what the circuit's current state is. A direct transition request can still be
     *               denied if <code>force = true</code>, but only because the active transition policy rejects it.
     *               This may happen if a certain policy's housekeeping cannot recover from external manipulation, for
     *               example--something that won't happen with this package's core policies but may happen with others.
     * @return true if the transition was valid, false if it was rejected because the policy disallowed it, or because
     *     <code>force</code> was <code>false</code> and the transition did not apply (e.g., we tried to close an
     *     {@link Circuit#OPEN OPEN} circuit without {@link Circuit#RESETTING} first).
     */
    public boolean directTransition(int desiredState, boolean force) {
        boolean ok = false;
        if (transitionPolicy.beforeDirectTransition(desiredState, force)) {
            if (force) {
                circuit.unsafeTransition(desiredState);
                ok = true;
            } else {
                int oldState = circuit.getStateSnapshot();
                if (Circuit.isValidTransition(oldState, desiredState)) {
                    ok = circuit.transition(circuit.getStateSnapshot(), desiredState);
                }
            }
        }
        if (ok) {
            transitionPolicy.afterDirectTransition(desiredState, force);
        }
        return ok;
    }
}
