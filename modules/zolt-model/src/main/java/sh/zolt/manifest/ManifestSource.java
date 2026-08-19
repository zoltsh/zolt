package sh.zolt.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

/** The portable manifest and canonical field that supplied one effective value. */
public record ManifestSource(String manifestPath, String fieldPath) {
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    public ManifestSource {
        Objects.requireNonNull(manifestPath, "Manifest source path must not be null.");
        Objects.requireNonNull(fieldPath, "Manifest source field must not be null.");
        validateManifestPath(manifestPath);
        if (fieldPath.isBlank()) {
            throw new IllegalArgumentException("Manifest source field must not be blank.");
        }
        rejectControls(fieldPath, "Manifest source field");
    }

    private static void validateManifestPath(String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("Manifest source path must not be blank.");
        }
        if (path.startsWith("/") || path.startsWith("\\") || DRIVE_PREFIX.matcher(path).matches()) {
            throw invalidPath(path, "must be workspace-relative");
        }
        if (path.indexOf('\\') >= 0) {
            throw invalidPath(path, "must use forward slashes");
        }
        rejectControls(path, "Manifest source path");
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalidPath(path, "contains an invalid path segment");
            }
        }
    }

    private static void rejectControls(String value, String subject) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(subject + " must not contain control characters.");
            }
        }
    }

    private static IllegalArgumentException invalidPath(String path, String reason) {
        return new IllegalArgumentException("Manifest source path `" + path + "` " + reason + ".");
    }
}
