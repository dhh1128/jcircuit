package co.codecraft.jcircuit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class CircuitTest {

    @Test
    public void null_listener_is_fine() {
        Circuit c = new Circuit(null);
        assertTrue(c.transition(c.getStateSnapshot(), Circuit.OPEN));
    }

    @Test
    public void redundant_transition_returns_true() {
        Circuit c = new Circuit(null);
        c.transition(c.getStateSnapshot(), Circuit.OPEN);
        assertTrue(c.transition(c.getStateSnapshot(), Circuit.OPEN));
    }

    @Test
    public void redundant_transition_doesnt_call_listener() {
        CapturingListener cl = new CapturingListener();
        Circuit c = new Circuit(cl);
        c.transition(c.getStateSnapshot(), Circuit.CLOSED);
        assertTrue(cl.transitions.isEmpty());
    }

    @Test
    public void listener_is_called_for_every_transition() {
        CapturingListener cl = new CapturingListener();
        Circuit c = new Circuit(cl);
        c.transition(c.getStateSnapshot(), Circuit.OPEN);
        c.transition(c.getStateSnapshot(), Circuit.RESETTING);
        c.transition(c.getStateSnapshot(), Circuit.CLOSED);
        c.transition(c.getStateSnapshot(), Circuit.OPEN);
        c.transition(c.getStateSnapshot(), Circuit.FAILED);
        cl.assertStates(0, 1, 2, 0, 1, 3);
    }

    @Test
    public void state_constants_are_ordered_correctly() {
        assertTrue(Circuit.CLOSED < Circuit.OPEN);
        assertTrue(Circuit.OPEN < Circuit.RESETTING);
        assertTrue(Circuit.RESETTING < Circuit.FAILED);
    }

    @Test
    public void state_constant_string_conversions_work() {
        for (int i = Circuit.CLOSED; i <= Circuit.FAILED; ++i) {
            assertEquals(i, Circuit.stringToState(Circuit.stateToString(i)));
        }
    }

    @Test
    public void thread_safety() {
        final CapturingListener cl = new CapturingListener();
        final Circuit c = new Circuit(cl);
        //c.shouldValidate = true;
        final AtomicInteger validCount = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<Thread>();
        // Create 5 well-behaved threads that will each contend to transition the state machine in valid
        // ways. Because there is contention and non-deterministic concurrency, we ought to have a fair
        // number of cases where transition() rejects a proposed change. However, if our thread safety
        // is correct, we should observe only valid transitions in the capture of what ended up happening.
        for (int i = 0; i < 5; ++i) {
            Thread th = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int j = 0; j < 1000; ++j) {
                        int snapshot = c.getStateSnapshot();
                        int newState;
                        switch (snapshot & Circuit.STATE_MASK) {
                            case Circuit.CLOSED:
                                newState = Circuit.OPEN;
                                break;
                            case Circuit.OPEN:
                            case Circuit.FAILED:
                                newState = Circuit.RESETTING;
                                break;
                            case Circuit.RESETTING:
                                newState = j % 3 == 0 ? Circuit.CLOSED : j % 3 == 1 ? Circuit.FAILED : Circuit.OPEN;
                                break;
                            default:
                                newState = 0; // just to make compiler happy
                        }
                        try {
                            Thread.sleep(0, 1000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            return;
                        }
                        if (c.transition(snapshot, newState)) {
                            validCount.incrementAndGet();
                        }
                    }
                }
            });
            threads.add(th);
            th.start();
        }
        for (int i = 0; i < 5; ++i) {
            try {
                threads.get(i).join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            }
        }
        //System.out.printf("validCount = %d\n", validCount.get());
        assertTrue(validCount.get() > 1500);
        assertTrue(validCount.get() <= 5000);
        for (int i = 0, j = 1; j < cl.transitions.size(); ++i, ++j) {
            int oldState = cl.transitions.get(i);
            int newState = cl.transitions.get(j);
            if (oldState == newState) {
                fail(String.format("Redundant (though not illegal) transition from %d to %d suggests a problem, since"
                        + " we shouldn't be recording redundant transitions.", oldState, newState));
            } else if (!Circuit.isValidTransition(oldState, newState)) {
                fail(String.format("Illegal transition from %d to %d suggests thread safety is broken.",
                        oldState, newState));
                break;
            }
        }
    }
}
