package co.codecraft.jcircuit;

/**
 * A simple, one-method interface that {@link TransitionPolicy} objects can use to decide when it's worth
 * considering a transition again. This is primarily an optimization; it allows policies to skip expensive
 * analysis most of the time, and only re-evaluate occasionally. As such, it is important to understand that
 * readiness is a measure of <em>eligibility</em>; it is not a measure of <em>fitness</em>. A policy that is
 * ready to evaluate a reset will transition its {@link Circuit} to the {@link Circuit#RESETTING} state, but
 * there is no guarantee that the reset attempt will succeed. Some policies will not need this optimization,
 * because their analysis work is already very lightweight.
 *
 * Implementations may decide readiness based on elapsed time ("yes if 10 seconds have
 * elapsed"), based on environmental data ("yes if the CPU is no longer pegged"), based on events ("yes if we've
 * received a signal from elsewhere"), etc.
 */
public interface ReadyTester {
    boolean isReady(TransitionPolicy policy);
}
