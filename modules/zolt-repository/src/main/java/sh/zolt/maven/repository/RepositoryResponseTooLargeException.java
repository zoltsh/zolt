package sh.zolt.maven.repository;

import java.io.IOException;

final class RepositoryResponseTooLargeException extends IOException {
    RepositoryResponseTooLargeException(String message) {
        super(message);
    }
}
