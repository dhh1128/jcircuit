package co.codecraft.jcircuit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.fail;

/**
 * Useful for capturing transitions that happen during a unit test.
 */
public class CapturingListener implements Circuit.Listener {
    public boolean debug = false;
    public final List<Integer> transitions = new ArrayList<Integer>();

    @Override
    public void onCircuitTransition(Circuit circuit, int oldState, int newState) {
        synchronized (transitions) {
            if (transitions.isEmpty()) {
                transitions.add(oldState);
            }
            transitions.add(newState);
            if (debug) {
                System.out.printf("%s: %d (%s) --> %d (%s)\n", getTimestamp(), oldState,
                        Circuit.stateToString(oldState), newState, Circuit.stateToString(newState));
            }
        }
    }

    private static long startTimeMillis = System.currentTimeMillis();
    public static String getTimestamp() {
        return String.format("%.3f", ((System.currentTimeMillis() - startTimeMillis) % 10000) / 1000.0);
    }

    public int[] getTransitionCounts() {
        int counts[] = new int[4];
        for (Integer n: transitions) {
            counts[n]+= 1;
        }
        return counts;
    }

    public int getFinalState() {
        return transitions.get(transitions.size() - 1);
    }

    /**
     * Asserts that we captured the states we expected. On failure, dumps out a comparison so finding
     * the discrepancy is easy.
     * @param expected A variadic list of states, typically beginning with {@link Circuit#CLOSED}.
     */
    public void assertStates(int... expected) {
        List<Integer> actual = transitions;
        boolean ok = actual.size() == expected.length;
        if (ok) {
            for (int i = 0; i < expected.length; ++i) {
                if (expected[i] != actual.get(i)) {
                    ok = false;
                    break;
                }
            }
        }
        if (!ok) {
            StringBuilder sb = new StringBuilder();
            sb.append("Transitions did not match.\n  Expected ");
            sb.append(expected.length);
            sb.append(" items: ");
            for (int i = 0; i < expected.length; ++i) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(expected[i]);
            }
            sb.append("\n  Actual ");
            sb.append(actual.size());
            sb.append(" items:   ");
            for (int i = 0; i < actual.size(); ++i) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(actual.get(i));
            }
            fail(sb.toString());
        }
    }
}

