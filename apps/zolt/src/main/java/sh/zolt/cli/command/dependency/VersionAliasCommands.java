package sh.zolt.cli.command.dependency;

import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.VersionPolicy;
import sh.zolt.update.AliasReferences;
import java.util.List;

final class VersionAliasCommands {
    private VersionAliasCommands() {
    }

    static LocalId validateAlias(String alias) {
        if (alias == null || alias.isBlank() || !alias.equals(alias.trim())) {
            throw new VersionAliasCommandException(
                    "Version alias must be non-empty and must not contain leading or trailing whitespace.");
        }
        try {
            return new LocalId(alias);
        } catch (IllegalArgumentException exception) {
            throw new VersionAliasCommandException(
                    "Invalid version alias `" + alias + "`. Alias names use lowercase kebab-case.");
        }
    }

    static String validateValue(LocalId alias, String version) {
        VersionPolicy.violation(VersionPolicy.Context.VERSION_ALIAS, version).ifPresent(violation -> {
            throw new VersionAliasCommandException(
                    "Invalid " + VersionPolicy.Context.VERSION_ALIAS.description() + " `" + version
                            + "` for [versions]." + alias + ". " + violation.guidance());
        });
        return version;
    }

    static List<String> references(AuthoredManifest manifest, LocalId alias) {
        return AliasReferences.referencingLabels(manifest, alias.value());
    }

    static final class VersionAliasCommandException extends RuntimeException {
        VersionAliasCommandException(String message) {
            super(message);
        }
    }
}
