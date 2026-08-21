package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredTask;

/** Cross-package test seam for package-private final-manifest command decoders. */
public final class ManifestCommandsTestSupport {
    private ManifestCommandsTestSupport() {
    }

    public static Optional<Map<LocalId, AuthoredTask>> decodeTasks(String source) {
        return new ManifestCommandsDecoder().decodeTasks(
                ManifestSemanticTestSupport.index(source));
    }

    public static void decodeTasksWithNullIndex() {
        new ManifestCommandsDecoder().decodeTasks(null);
    }

    public static Optional<Map<LocalId, AuthoredAlias>> decodeAliases(String source) {
        return new ManifestCommandsDecoder().decodeAliases(
                ManifestSemanticTestSupport.index(source));
    }

    public static void decodeAliasesWithNullIndex() {
        new ManifestCommandsDecoder().decodeAliases(null);
    }
}
