package sh.zolt.manifest.adapter;

import java.util.LinkedHashMap;
import java.util.Map;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.effective.EffectiveValue;

/** Resolves final version selectors to the exact literal versions the legacy engine expects. */
final class ProjectConfigVersions {
    private ProjectConfigVersions() {
    }

    /** The effective {@code [versions]} map as the legacy alias-name to literal-version map. */
    static Map<String, String> aliases(Map<LocalId, EffectiveValue<VersionAliasValue>> versions) {
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        versions.forEach((alias, value) -> aliases.put(alias.value(), value.value().value()));
        return Map.copyOf(aliases);
    }

    /** The literal version behind a platform selector. */
    static String resolve(
            PlatformSelector selector,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            String subject) {
        return switch (selector) {
            case PlatformSelector.FixedVersion fixed -> fixed.value();
            case PlatformSelector.VersionReference reference ->
                    alias(versions, reference.alias(), subject);
        };
    }

    /** The alias name behind a platform selector, or {@code null} for a fixed version. */
    static String reference(PlatformSelector selector) {
        return selector instanceof PlatformSelector.VersionReference reference
                ? reference.alias().value()
                : null;
    }

    /** The literal version behind a fixed-or-reference dependency selector. */
    static String resolve(
            DependencySelector selector,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            String subject) {
        return switch (selector) {
            case DependencySelector.FixedVersion fixed -> fixed.value();
            case DependencySelector.VersionReference reference ->
                    alias(versions, reference.alias(), subject);
            case DependencySelector.Managed ignored -> throw unsupported(subject);
            case DependencySelector.Workspace ignored -> throw unsupported(subject);
        };
    }

    /** The alias name behind a dependency selector, or {@code null} for a fixed version. */
    static String reference(DependencySelector selector) {
        return selector instanceof DependencySelector.VersionReference reference
                ? reference.alias().value()
                : null;
    }

    private static String alias(
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            LocalId alias,
            String subject) {
        EffectiveValue<VersionAliasValue> value = versions.get(alias);
        if (value == null) {
            throw new IllegalArgumentException(
                    subject + " references undefined version alias `" + alias + "`.");
        }
        return value.value().value();
    }

    private static IllegalArgumentException unsupported(String subject) {
        return new IllegalArgumentException(
                subject + " requires a fixed version or version reference.");
    }
}
