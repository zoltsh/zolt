package sh.zolt.build.run;

public record JavaRunResult(
        String mainClass,
        String diagnosticTail,
        boolean endedWithNewline,
        int signal,
        boolean terminationInitiatedByZolt) {
    private static final int NOT_SIGNALLED = -1;

    public JavaRunResult(String mainClass, String output) {
        this(mainClass, output, endsWithNewline(output), NOT_SIGNALLED, false);
    }

    public JavaRunResult(String mainClass, String output, int signal) {
        this(mainClass, output, endsWithNewline(output), signal, false);
    }

    public boolean signalled() {
        return signal != NOT_SIGNALLED;
    }

    /** Compatibility name for callers that consume the retained diagnostic tail. */
    public String output() {
        return diagnosticTail;
    }

    private static boolean endsWithNewline(String output) {
        return output != null && !output.isEmpty() && output.endsWith("\n");
    }
}
