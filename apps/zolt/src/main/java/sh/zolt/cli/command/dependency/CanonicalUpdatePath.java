package sh.zolt.cli.command.dependency;

import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Converts an authoritative filesystem path to one canonical mutation-root-relative POSIX path. */
final class CanonicalUpdatePath {
    private CanonicalUpdatePath() {
    }

    static String relative(Path mutationRoot, Path path) {
        String relative = rawRelative(mutationRoot, path);
        if (relative.indexOf('\\') >= 0) {
            throw new ZoltConfigException(
                    "Dependency update paths cannot contain backslashes. No update target was produced.");
        }
        if (!Normalizer.isNormalized(relative, Normalizer.Form.NFC)) {
            throw new ZoltConfigException(
                    "Dependency update path is not a canonical Unicode NFC path. No update target was produced.");
        }
        return relative;
    }

    static String rawRelative(Path mutationRoot, Path path) {
        Path root = mutationRoot.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new ZoltConfigException(
                    "Dependency update path is outside its mutation root. No update target was produced.");
        }
        List<String> segments = new ArrayList<>();
        for (Path segment : root.relativize(normalized)) {
            String value = segment.toString();
            segments.add(value);
        }
        String relative = String.join("/", segments);
        if (relative.isBlank()) {
            throw new ZoltConfigException("Dependency update path is empty. No update target was produced.");
        }
        return relative;
    }
}
