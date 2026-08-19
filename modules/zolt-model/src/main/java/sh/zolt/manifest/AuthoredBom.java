package sh.zolt.manifest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authored BOM sections without resolving aliases or workspace membership. */
public record AuthoredBom(
        Optional<Members> members,
        Optional<Map<DependencyCoordinate, Version>> versions,
        Optional<Map<DependencyCoordinate, PlatformSelector>> imports) {
    public AuthoredBom {
        members = Objects.requireNonNull(members, "Authored BOM members must not be null.");
        versions = Objects.requireNonNull(versions, "Authored BOM versions must not be null.")
                .map(AuthoredBom::immutableVersions);
        imports = Objects.requireNonNull(imports, "Authored BOM imports must not be null.")
                .map(AuthoredBom::immutableImports);
        if (members.isEmpty() && versions.isEmpty() && imports.isEmpty()) {
            throw new IllegalArgumentException("Authored BOM settings must contain at least one BOM domain.");
        }
    }

    /** The required contents of an explicitly authored {@code [bom]} table. */
    public record Members(MemberSelection selection, List<WorkspaceMemberPath> exclude) {
        public Members {
            Objects.requireNonNull(selection, "Authored BOM member selection must not be null.");
            exclude = immutableMemberPaths(exclude, "BOM member exclusions");
            if (!(selection instanceof AllMembers) && !exclude.isEmpty()) {
                throw new IllegalArgumentException(
                        "BOM member exclusions are valid only with `members = true`.");
            }
        }
    }

    /** The two valid shapes of the required {@code bom.members} field. */
    public sealed interface MemberSelection permits AllMembers, ExplicitMembers {
    }

    /** Select every consumable member, subject to optional exact exclusions. */
    public record AllMembers() implements MemberSelection {
    }

    /** Select a nonempty deterministic list of exact workspace member paths. */
    public record ExplicitMembers(List<WorkspaceMemberPath> paths) implements MemberSelection {
        public ExplicitMembers {
            paths = immutableMemberPaths(paths, "Explicit BOM members");
            if (paths.isEmpty()) {
                throw new IllegalArgumentException("Explicit BOM members must not be empty.");
            }
        }
    }

    /** One {@code [bom.versions]} entry with an optional Maven artifact variant. */
    public record Version(
            PlatformSelector selector,
            Optional<String> classifier,
            Optional<String> type) {
        public Version {
            Objects.requireNonNull(selector, "Authored BOM version selector must not be null.");
            classifier = Objects.requireNonNull(
                            classifier, "Authored BOM classifier must not be null.")
                    .map(DependencyVariantValue::classifier);
            type = Objects.requireNonNull(type, "Authored BOM type must not be null.")
                    .map(DependencyVariantValue::type);
        }
    }

    private static Map<DependencyCoordinate, Version> immutableVersions(
            Map<DependencyCoordinate, Version> values) {
        return ManifestModelValues.immutableSortedMap(
                values,
                java.util.Comparator.naturalOrder(),
                "BOM version coordinate",
                "Authored BOM version");
    }

    private static Map<DependencyCoordinate, PlatformSelector> immutableImports(
            Map<DependencyCoordinate, PlatformSelector> values) {
        return ManifestModelValues.immutableSortedMap(
                values,
                java.util.Comparator.naturalOrder(),
                "BOM import coordinate",
                "Authored BOM import");
    }

    private static List<WorkspaceMemberPath> immutableMemberPaths(
            List<WorkspaceMemberPath> values, String label) {
        List<WorkspaceMemberPath> copy = ManifestModelValues.sortedDistinctList(values, label);
        Map<String, WorkspaceMemberPath> spellingByPortabilityKey = new HashMap<>();
        for (WorkspaceMemberPath path : copy) {
            WorkspaceMemberPath existing = spellingByPortabilityKey.putIfAbsent(
                    path.portabilityKey(), path);
            if (existing != null && !existing.equals(path)) {
                throw new IllegalArgumentException(
                        label + " paths `" + existing + "` and `" + path
                                + "` collide under Unicode case-fold comparison.");
            }
        }
        return copy;
    }
}
