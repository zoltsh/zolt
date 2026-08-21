package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredCommands;
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
                ManifestSemanticTestSupport.index(source), (id, alias) -> {});
    }

    public static void decodeAliasesWithNullIndex() {
        new ManifestCommandsDecoder().decodeAliases(null, (id, alias) -> {});
    }

    public static void decodeAliasesWithNullObserver(String source) {
        new ManifestCommandsDecoder().decodeAliases(ManifestSemanticTestSupport.index(source), null);
    }

    public static Optional<AuthoredCommands> decodeCommands(String source) {
        return new ManifestCommandsDecoder().decode(ManifestSemanticTestSupport.index(source));
    }

    public static void decodeCommandsWithNullIndex() {
        new ManifestCommandsDecoder().decode(null);
    }
}
