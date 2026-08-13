package sh.zolt.process;

import java.util.Set;

/** Interprets the conventional POSIX 128+signal exit values without treating arbitrary high codes as signals. */
public final class ProcessExitInterpreter {
    private static final int SIGNAL_EXIT_BASE = 128;
    private static final int NOT_SIGNALLED = -1;
    private static final Set<Integer> CONVENTIONAL_SIGNALS = Set.of(1, 2, 3, 6, 9, 14, 15);

    private ProcessExitInterpreter() {
    }

    public static int signal(int exitCode) {
        if (isWindows()) {
            return NOT_SIGNALLED;
        }
        int candidate = exitCode - SIGNAL_EXIT_BASE;
        return CONVENTIONAL_SIGNALS.contains(candidate) ? candidate : NOT_SIGNALLED;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
