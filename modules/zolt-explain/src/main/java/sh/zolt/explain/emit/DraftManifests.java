package sh.zolt.explain.emit;

import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredProject;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredProjectMetadata;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.manifest.authored.AuthoredWorkspace;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Assembles drafted audit facts into an {@link AuthoredManifest} in the final language.
 *
 * <p>Only the domains the audit could read are populated; everything else stays absent so the
 * canonical writer emits a sparse manifest (design §5.1). Foreign identity values are validated
 * against the manifest grammar here, because a Maven artifactId or a Gradle project name is not
 * guaranteed to be a legal Zolt project name or workspace ID.
 */
final class DraftManifests {
    private DraftManifests() {
    }

    static AuthoredManifest project(
            AuthoredProjectIdentity identity,
            AuthoredProjectMetadata metadata,
            DraftDependencies dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            AuthoredBuildConfiguration build,
            Optional<AuthoredGeneratedSources> generated,
            AuthoredPackaging packaging) {
        return project(
                identity,
                metadata,
                dependencies.dependencies(),
                dependencies.platformDomain(),
                constraints,
                build,
                generated,
                packaging);
    }

    static AuthoredManifest project(
            AuthoredProjectIdentity identity,
            AuthoredProjectMetadata metadata,
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredPlatforms> platforms,
            Optional<AuthoredDependencyConstraints> constraints,
            AuthoredBuildConfiguration build,
            Optional<AuthoredGeneratedSources> generated,
            AuthoredPackaging packaging) {
        return new AuthoredManifest(
                Optional.empty(),
                Optional.of(new AuthoredProject(identity, metadata)),
                AuthoredToolchains.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                platforms,
                dependencies,
                constraints,
                Optional.empty(),
                build,
                generated,
                packaging,
                Optional.empty(),
                Optional.empty());
    }

    /** A virtual workspace root: no project-only domain may be present (design §4.5). */
    static AuthoredManifest workspaceRoot(AuthoredWorkspace workspace) {
        return new AuthoredManifest(
                Optional.of(workspace),
                Optional.empty(),
                AuthoredToolchains.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                AuthoredPackaging.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static AuthoredProjectIdentity identity(
            String name,
            Optional<String> group,
            Optional<String> version,
            Optional<Integer> javaRelease,
            List<String> notes) {
        return new AuthoredProjectIdentity(
                projectName(name, notes),
                version.flatMap(value -> projectVersion(value, notes)),
                group.flatMap(value -> projectGroup(value, notes)),
                javaRelease.map(JavaFeatureRelease::new),
                Optional.empty());
    }

    static AuthoredProjectMetadata metadata(Optional<String> mainClass, List<String> notes) {
        return new AuthoredProjectMetadata(
                mainClass.flatMap(value -> mainClass(value, notes)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                java.util.Map.of());
    }

    /**
     * A workspace name in the {@code [a-z][a-z0-9]*(-[a-z0-9]+)*} local-ID grammar (design §15.3),
     * derived from the foreign root project name when that name is not already legal.
     */
    static LocalId workspaceName(String name, List<String> notes) {
        try {
            return new LocalId(name);
        } catch (IllegalArgumentException exception) {
            String sanitized = sanitizeLocalId(name);
            notes.add("Root project name `" + name + "` is not a valid workspace ID; the draft uses `"
                    + sanitized + "`. Workspace IDs are lowercase kebab-case (design §15.3).");
            return new LocalId(sanitized);
        }
    }

    private static ProjectName projectName(String name, List<String> notes) {
        try {
            return new ProjectName(name);
        } catch (IllegalArgumentException exception) {
            String sanitized = sanitizeProjectName(name);
            notes.add("Project name `" + name + "` uses characters a Maven artifact ID cannot carry;"
                    + " the draft uses `" + sanitized + "`. Set `[project].name` to your real artifact ID.");
            return new ProjectName(sanitized);
        }
    }

    private static Optional<ProjectVersion> projectVersion(String version, List<String> notes) {
        try {
            return Optional.of(new ProjectVersion(version));
        } catch (IllegalArgumentException exception) {
            notes.add("Project version `" + version + "` is not a valid manifest value: "
                    + exception.getMessage() + " Set `[project].version` by hand.");
            return Optional.empty();
        }
    }

    private static Optional<ProjectGroup> projectGroup(String group, List<String> notes) {
        try {
            return Optional.of(new ProjectGroup(group));
        } catch (IllegalArgumentException exception) {
            notes.add("Project group `" + group + "` is not a valid manifest value: "
                    + exception.getMessage() + " Set `[project].group` by hand.");
            return Optional.empty();
        }
    }

    private static Optional<JavaBinaryClassName> mainClass(String value, List<String> notes) {
        try {
            return Optional.of(new JavaBinaryClassName(value));
        } catch (IllegalArgumentException exception) {
            notes.add("Main class `" + value + "` is not a fully qualified Java binary name: "
                    + exception.getMessage() + " Set `[project].main` by hand.");
            return Optional.empty();
        }
    }

    private static String sanitizeLocalId(String value) {
        String sanitized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^[^a-z]+", "")
                .replaceAll("-+$", "");
        return sanitized.isBlank() ? "workspace" : sanitized;
    }

    private static String sanitizeProjectName(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_.-]+", "-");
        return sanitized.isBlank() ? "project" : sanitized;
    }
}
