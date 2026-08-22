package sh.zolt.cli.command.dependency;

import sh.zolt.manifest.PlatformSelector;
import sh.zolt.project.VersionPolicy;
import java.util.function.Function;

/**
 * Reads the one selector a platform, BOM pin, or BOM import declares.
 *
 * <p>A fixed version is a separate positional value and is mutually exclusive with
 * {@code --version-ref} (design §20), so exactly one of the two always reaches the manifest.
 */
final class PlatformSelectors {
    private PlatformSelectors() {
    }

    static PlatformSelector parse(
            DependencyEditCommands.VersionAliasView aliases,
            String subject,
            String version,
            String versionRef,
            Function<String, RuntimeException> failure) {
        if (versionRef != null && versionRef.isBlank()) {
            throw failure.apply(
                    "Version alias for --version-ref must be non-empty. Use `--version-ref <alias>`.");
        }
        if (versionRef != null && version != null) {
            throw failure.apply("A " + subject + " declares either a version or `--version-ref <alias>`, "
                    + "not both. Remove the positional version or the option.");
        }
        if (versionRef != null) {
            DependencyEditCommands.requireAlias(aliases, versionRef, failure);
            return new PlatformSelector.VersionReference(
                    DependencyEditCommands.localId(versionRef, failure));
        }
        if (version == null) {
            throw failure.apply("A " + subject + " requires a version. Use `group:artifact <version>` "
                    + "or `--version-ref <alias>`.");
        }
        DependencyEditCommands.validateCommandVersion(
                VersionPolicy.Context.PLATFORM, subject, version, failure);
        return new PlatformSelector.FixedVersion(version);
    }
}
