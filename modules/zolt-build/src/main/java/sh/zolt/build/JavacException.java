package sh.zolt.build;

import sh.zolt.error.ActionableError;
import sh.zolt.error.HasActionableError;

public final class JavacException extends SourceCompileException implements HasActionableError {
    private final transient ActionableError error;

    public JavacException(String message) {
        super(message);
        this.error = null;
    }

    public JavacException(String message, Throwable cause) {
        super(message, cause);
        this.error = null;
    }

    public JavacException(ActionableError error) {
        super(error.message(), error.cause().orElse(null));
        this.error = error;
    }

    public static JavacException actionable(String summary, String remediation) {
        return new JavacException(ActionableError.of(summary, remediation));
    }

    @Override
    public ActionableError actionableError() {
        return error;
    }
}
