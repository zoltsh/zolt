package sh.zolt.update;

/** Raised when raw project identity cannot be represented by the canonical schema-v2 contract. */
public final class UpdateTargetIdentityException extends IllegalArgumentException {
    public UpdateTargetIdentityException(String message) {
        super(message);
    }
}
