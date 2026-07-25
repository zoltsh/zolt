package sh.zolt.lockfile;

/** An existing lock cannot be interpreted as a complete dependency graph without guessing. */
public final class LockDependencyGraphException extends RuntimeException {
    public LockDependencyGraphException(String message) {
        super(message);
    }
}
