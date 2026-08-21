package sh.zolt.manifest.effective;

import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredManifest;

/** Fails closed on references that can be resolved within one standalone manifest. */
final class StandaloneManifestIntegrityValidator {
    void validate(AuthoredManifest authored) {
        Set<LocalId> versions = authored.versions()
                .map(value -> value.entries().keySet())
                .orElseGet(Set::of);
        validateDependencies(authored, versions);
        validateConstraints(authored, versions);
        validatePlatforms(authored, versions);
        validateGenerated(authored, versions);
        validateBom(authored, versions);
        validateCredentials(authored);
    }

    private static void validateDependencies(
            AuthoredManifest authored,
            Set<LocalId> versions) {
        boolean hasPlatforms = authored.platforms()
                .filter(value -> !value.entries().isEmpty())
                .isPresent();
        authored.dependencies().ifPresent(dependencies -> {
            for (AuthoredDependency dependency : dependencies.declarations()) {
                String subject = "Dependency `" + dependency.coordinate() + "`";
                requireVersionAlias(dependency.selector(), versions, subject);
                if (dependency.selector() instanceof DependencySelector.Workspace) {
                    throw new IllegalArgumentException(
                            subject + " cannot use `workspace = true` in a standalone manifest.");
                }
                if (dependency.selector() instanceof DependencySelector.Managed && !hasPlatforms) {
                    throw new IllegalArgumentException(
                            subject + " uses `managed = true` but no [platforms] entry is available.");
                }
            }
        });
    }

    private static void validateConstraints(
            AuthoredManifest authored,
            Set<LocalId> versions) {
        authored.dependencyConstraints().ifPresent(constraints -> constraints.entries()
                .forEach((coordinate, constraint) -> requireConstraintAlias(
                        constraint, versions, "Dependency constraint `" + coordinate + "`")));
    }

    private static void validatePlatforms(
            AuthoredManifest authored,
            Set<LocalId> versions) {
        authored.platforms().ifPresent(platforms -> platforms.entries().forEach(
                (coordinate, selector) -> requireVersionAlias(
                        selector, versions, "Platform `" + coordinate + "`")));
    }

    private static void validateGenerated(
            AuthoredManifest authored,
            Set<LocalId> versions) {
        authored.generated().ifPresent(generated -> generated.tools().declarations()
                .forEach((id, tool) -> validateGeneratedTool(id, tool, versions)));
    }

    private static void validateGeneratedTool(
            LocalId id,
            AuthoredGeneratedTool tool,
            Set<LocalId> versions) {
        String subject = "Generated tool `" + id + "`";
        switch (tool) {
            case AuthoredGeneratedTool.OpenApi openApi -> openApi.version()
                    .ifPresent(selector -> requireVersionAlias(selector, versions, subject));
            case AuthoredGeneratedTool.Protobuf protobuf -> {
                protobuf.protocVersion().ifPresent(selector -> requireVersionAlias(
                        selector, versions, subject + " protoc request"));
                protobuf.grpcVersion().ifPresent(selector -> requireVersionAlias(
                        selector, versions, subject + " gRPC request"));
            }
            case AuthoredGeneratedTool.Jvm jvm -> {
                for (GeneratedArtifactRequest request : jvm.coordinates()) {
                    requireVersionAlias(
                            request.selector(),
                            versions,
                            subject + " coordinate `" + request.coordinate() + "`");
                }
            }
            case AuthoredGeneratedTool.Process ignored -> {
                // Process tools have no shared version-alias references.
            }
        }
    }

    private static void validateBom(
            AuthoredManifest authored,
            Set<LocalId> versions) {
        authored.packaging().bom().ifPresent(bom -> {
            if (bom.members().isPresent()) {
                throw new IllegalArgumentException(
                        "A standalone BOM cannot declare workspace members or exclusions.");
            }
            bom.versions().ifPresent(entries -> entries.forEach((coordinate, version) ->
                    requireVersionAlias(
                            version.selector(), versions, "BOM version `" + coordinate + "`")));
            bom.imports().ifPresent(entries -> entries.forEach((coordinate, selector) ->
                    requireVersionAlias(
                            selector, versions, "BOM import `" + coordinate + "`")));
        });
    }

    private static void validateCredentials(AuthoredManifest authored) {
        Set<LocalId> credentials = authored.credentials()
                .map(value -> value.entries().keySet())
                .orElseGet(Set::of);
        authored.repositories().ifPresent(repositories -> repositories.credentialReferences()
                .stream()
                .sorted()
                .forEach(reference ->
                        requireCredential(reference, credentials, "Dependency repository")));
        authored.publishing().ifPresent(publishing -> publishing.credentialReferences()
                .forEach(reference ->
                        requireCredential(reference, credentials, "Publication repository")));
    }

    private static void requireConstraintAlias(
            AuthoredDependencyConstraint constraint,
            Set<LocalId> versions,
            String subject) {
        if (constraint.selector() instanceof DependencyConstraintSelector.VersionReference reference) {
            requireAlias(reference.alias(), versions, subject);
        }
    }

    private static void requireVersionAlias(
            Object selector,
            Set<LocalId> versions,
            String subject) {
        Optional<LocalId> alias = switch (selector) {
            case DependencySelector.VersionReference reference -> Optional.of(reference.alias());
            case DependencySelector.FixedVersion ignored -> Optional.empty();
            case DependencySelector.Managed ignored -> Optional.empty();
            case DependencySelector.Workspace ignored -> Optional.empty();
            case PlatformSelector.VersionReference reference -> Optional.of(reference.alias());
            case PlatformSelector.FixedVersion ignored -> Optional.empty();
            default -> throw new IllegalArgumentException(
                    "Unsupported standalone version selector for " + subject + ".");
        };
        alias.ifPresent(value -> requireAlias(value, versions, subject));
    }

    private static void requireAlias(
            LocalId alias,
            Set<LocalId> versions,
            String subject) {
        if (!versions.contains(alias)) {
            throw new IllegalArgumentException(
                    subject + " references undefined version alias `" + alias + "`.");
        }
    }

    private static void requireCredential(
            LocalId credential,
            Set<LocalId> credentials,
            String subject) {
        if (!credentials.contains(credential)) {
            throw new IllegalArgumentException(
                    subject + " references undefined credential `" + credential + "`.");
        }
    }
}
