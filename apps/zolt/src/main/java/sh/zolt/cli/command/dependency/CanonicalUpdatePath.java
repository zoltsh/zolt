package sh.zolt.cli.command.dependency;

import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import java.text.Normalizer;

/** Converts an authoritative filesystem path to one canonical mutation-root-relative POSIX path. */
final class CanonicalUpdatePath {
    private CanonicalUpdatePath() {
    }

    static String relative(Path mutationRoot, Path path) {
        Path root = mutationRoot.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new ZoltConfigException(
                    "Dependency update path is outside its mutation root. No update target was produced.");
        }
        String relative = root.relativize(normalized).toString().replace('\\', '/');
        if (relative.isBlank() || !Normalizer.isNormalized(relative, Normalizer.Form.NFC)) {
            throw new ZoltConfigException(
                    "Dependency update path is not a canonical Unicode NFC path. No update target was produced.");
        }
        return relative;
    }
}
