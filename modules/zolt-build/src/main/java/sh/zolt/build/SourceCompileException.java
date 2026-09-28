package sh.zolt.build;

/** Base type for user-facing main and test source compiler failures. */
public abstract class SourceCompileException extends RuntimeException {
    protected SourceCompileException(String message) {
        super(message);
    }

    protected SourceCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
