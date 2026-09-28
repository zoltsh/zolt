package sh.zolt.build;

/**
 * Version of the compilation semantics that determine whether previously produced class output may
 * be reused.
 *
 * <p>Every reuse layer that can bypass compilation must include this token in its persisted key.
 * Increment it whenever a compiler-strategy or fingerprint-model change makes output accepted by an
 * earlier version unsafe to reuse.
 */
public final class CompilationSemantics {
    /**
     * Version 4 adds full-scope Groovy/Java joint compilation for supported main source sets.
     */
    public static final String VERSION = "4";

    private CompilationSemantics() {}
}
