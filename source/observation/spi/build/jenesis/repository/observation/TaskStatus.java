package build.jenesis.repository.observation;

import module java.base;

/**
 * The status of one background task a plugin runs (GC / reclamation, the sizes and dependents sweeps, the scheduled
 * scan and cleanup, the forwarding watermark, the continuous re-analysis sweep): a stable {@code jenesis.<feature>.<task>}
 * {@code name}, a human-readable {@code description}, its {@link State}, when it {@code lastRun} (null - never), how long
 * that run took ({@code lastDuration}, null when unknown) and the plain {@code outcome} of that run. So an operator - or a
 * headless agent following the console - sees each task's last-run and state rather than trusting a thread to stay up
 * unnoticed. Immutable; the name is validated at construction against {@link Signals}.
 */
public record TaskStatus(String name, String description, State state, Instant lastRun, Duration lastDuration,
                         String outcome) {

    /** Between runs, running now, switched off, failed its last run, or of unknown state. */
    public enum State { IDLE, RUNNING, DISABLED, FAILED, UNKNOWN }

    public TaskStatus {
        Signals.require(name);
        description = Objects.requireNonNull(description, "description");
        state = Objects.requireNonNull(state, "state");
        outcome = outcome == null ? "" : outcome;
    }

    /** A task that has never run. */
    public static TaskStatus idle(String name, String description) {
        return new TaskStatus(name, description, State.IDLE, null, null, "");
    }

    /** A task whose last run finished in {@code state} with {@code outcome} at {@code lastRun}, taking
     *  {@code lastDuration}. */
    public static TaskStatus ran(String name, String description, State state, Instant lastRun, Duration lastDuration,
                                 String outcome) {
        return new TaskStatus(name, description, state, lastRun, lastDuration, outcome);
    }

    /** Whether the task is known to have run at least once. */
    public boolean everRan() {
        return lastRun != null;
    }
}
