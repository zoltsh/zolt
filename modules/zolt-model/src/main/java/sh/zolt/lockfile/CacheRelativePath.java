package sh.zolt.lockfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A platform-neutral path to one artifact below Zolt's cache root.
 *
 * <p>Lockfiles always use forward slashes. Backslashes are rejected on every platform instead of
 * changing meaning between the machine that wrote a lock and the machine that consumes it.
 */
public record CacheRelativePath(String value) {
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    public CacheRelativePath {
        Objects.requireNonNull(value, "Cache-relative path must not be null.");
        validate(value);
    }

    /**
     * Resolves this path below {@code cacheRoot}, rejecting both lexical escapes and escapes through
     * any existing symbolic link. A contained symbolic link remains valid.
     */
    public Path resolveWithin(Path cacheRoot) {
        Objects.requireNonNull(cacheRoot, "Cache root must not be null.");
        Path root = cacheRoot.toAbsolutePath().normalize();
        Path target = root.resolve(value).normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw invalid("escapes the cache root");
        }
        try {
            Path realRoot = resolveExistingPrefix(root);
            Path realTarget = resolveExistingPrefix(target);
            if (realTarget.equals(realRoot) || !realTarget.startsWith(realRoot)) {
                throw invalid("escapes the cache root through a symbolic link");
            }
            return cacheRoot.normalize().resolve(value).normalize();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Cache-relative path `" + value + "` cannot be resolved safely: "
                            + exception.getMessage(),
                    exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }

    private static void validate(String value) {
        if (value.isBlank()) {
            throw invalid(value, "is empty");
        }
        if (value.startsWith("/") || value.startsWith("\\")) {
            throw invalid(value, "is absolute or UNC-prefixed");
        }
        if (DRIVE_PREFIX.matcher(value).matches()) {
            throw invalid(value, "is drive-prefixed");
        }
        if (value.indexOf('\\') >= 0) {
            throw invalid(value, "contains a backslash; lockfile cache paths use forward slashes");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw invalid(value, "contains a control character");
            }
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw invalid(value, "contains an empty path segment");
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw invalid(value, "contains a `" + segment + "` path segment");
            }
        }
    }

    private static Path resolveExistingPrefix(Path path) throws IOException {
        Deque<Path> missing = new ArrayDeque<>();
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("no existing path ancestor");
        }
        Path resolved = existing.toRealPath();
        for (Path segment : missing) {
            resolved = resolved.resolve(segment);
        }
        return resolved.normalize();
    }

    private IllegalArgumentException invalid(String reason) {
        return invalid(value, reason);
    }

    private static IllegalArgumentException invalid(String value, String reason) {
        return new IllegalArgumentException("Invalid cache-relative path `" + value + "`: " + reason + ".");
    }
}
