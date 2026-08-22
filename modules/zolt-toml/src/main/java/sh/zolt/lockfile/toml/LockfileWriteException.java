package sh.zolt.lockfile.toml;

final class LockfileWriteException extends RuntimeException {
    LockfileWriteException(String message) {
        super(message);
    }

    LockfileWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
