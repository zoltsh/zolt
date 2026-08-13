package sh.zolt.process;

/** Bounded process evidence retained after all live output has been streamed. */
public record SupervisedProcessResult(
        int exitCode,
        String diagnosticTail,
        boolean endedWithNewline,
        boolean timedOut,
        boolean terminationInitiatedByZolt,
        int signal) {
    private static final int NOT_SIGNALLED = -1;

    public boolean signalled() {
        return signal != NOT_SIGNALLED;
    }
}
