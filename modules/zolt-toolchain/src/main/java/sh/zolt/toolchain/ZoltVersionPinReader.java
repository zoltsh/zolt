package sh.zolt.toolchain;

import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the {@code [toolchain.zolt]} pin that governs one directory.
 *
 * <p>The search walks upward and stops at the first manifest that declares a pin, so a workspace
 * root's authoritative pin is found from any member directory (design §11.1, §4.5). Reading is
 * authored-only: the pin is a property of the file, not of an effective project, and a virtual
 * workspace root has no project to compose.
 */
public final class ZoltVersionPinReader {
    private static final String MANIFEST = "zolt.toml";

    private final ManifestProjectConfigLoader loader;

    public ZoltVersionPinReader() {
        this(new ManifestProjectConfigLoader());
    }

    ZoltVersionPinReader(ManifestProjectConfigLoader loader) {
        this.loader = loader;
    }

    /** The nearest declared pin at or above {@code startDirectory}. */
    public Optional<ZoltVersionRequirement> find(Path startDirectory) {
        Path current = startDirectory.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            Path manifest = current.resolve(MANIFEST);
            if (Files.isRegularFile(manifest)) {
                Optional<ZoltVersionRequirement> requirement = read(manifest);
                if (requirement.isPresent()) {
                    return requirement;
                }
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /** The pin declared by one manifest, if it declares one. */
    public Optional<ZoltVersionRequirement> read(Path manifestPath) {
        Path normalized = manifestPath.toAbsolutePath().normalize();
        return loader.document(normalized).authored().toolchains().zolt()
                .map(ZoltVersionPin::value)
                .map(version -> new ZoltVersionRequirement(normalized, version));
    }
}
