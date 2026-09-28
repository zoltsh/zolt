package sh.zolt.build;

public final class KotlinCompileException extends RuntimeException {
    public KotlinCompileException(String message) {
        super(message);
    }

    public KotlinCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
