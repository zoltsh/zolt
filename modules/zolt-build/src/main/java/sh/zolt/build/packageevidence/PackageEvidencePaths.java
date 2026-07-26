package sh.zolt.build.packageevidence;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class PackageEvidencePaths {
    private PackageEvidencePaths() {
    }

    static Optional<Path> resolveConfined(
            Path root,
            String value,
            String description,
            List<String> problems) {
        try {
            Path recorded = Path.of(value);
            if (recorded.isAbsolute()) {
                problems.add(
                        description
                                + " path `"
                                + value
                                + "` must be project-relative");
                return Optional.empty();
            }
            Path resolved = root.resolve(recorded).toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                problems.add(
                        description
                                + " path `"
                                + value
                                + "` escapes the project root");
                return Optional.empty();
            }
            return Optional.of(resolved);
        } catch (InvalidPathException exception) {
            problems.add(description + " path `" + value + "` is invalid");
            return Optional.empty();
        }
    }

    static String display(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString()
                : normalized.toString();
    }
}
