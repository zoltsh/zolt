package sh.zolt.explain.emit;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleProjectInspection;
import sh.zolt.explain.gradle.GradleRepositoryInspection;
import sh.zolt.explain.gradle.GradleVersionCatalogAlias;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Maps a single-project {@link GradleInspectionResult} to a {@link DraftZoltToml}. */
final class GradleInspectionMapper {
    static final String PLACEHOLDER_GROUP = "com.example";
    static final String PLACEHOLDER_VERSION = "0.1.0";

    private GradleInspectionMapper() {
    }

    static DraftZoltToml map(GradleInspectionResult result) {
        List<String> notes = skippedIncludedProjectNotes(result);
        GradleProjectInspection primary = result.projects().get(0);
        return mapProject(
                primary, null, result.versionCatalogAliases(), DraftIdentityDefaults.none(), notes);
    }

    /** Maps one subproject, rewriting {@code project(...)} edges to {@code { workspace = true }}. */
    static DraftZoltToml mapMember(
            GradleProjectInspection project,
            WorkspaceMemberRegistry registry,
            List<GradleVersionCatalogAlias> aliases,
            DraftIdentityDefaults defaults) {
        return mapProject(project, registry, aliases, defaults, new ArrayList<>());
    }

    static List<String> skippedIncludedProjectNotes(GradleInspectionResult result) {
        List<String> inspectedMembers = result.projects().stream()
                .map(project -> path(project.path().toString()))
                .filter(path -> !".".equals(path))
                .toList();
        List<String> skippedMembers = result.includedProjects().stream()
                .filter(path -> !inspectedMembers.contains(path))
                .toList();
        if (skippedMembers.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(
                "Gradle settings included " + skippedMembers.size()
                        + " project(s) that the static audit could not map to a build file: "
                        + String.join(", ", skippedMembers)
                        + ". These members are not emitted in this draft; review the explain signals before use."));
    }

    static String emittedCoordinate(GradleProjectInspection project) {
        return emittedGroup(project) + ":" + project.name();
    }

    private static DraftZoltToml mapProject(
            GradleProjectInspection primary,
            WorkspaceMemberRegistry registry,
            List<GradleVersionCatalogAlias> aliases,
            DraftIdentityDefaults defaults,
            List<String> notes) {
        // A standalone java-platform project drafts a [bom] member. Workspace members keep the existing
        // platform/dependency routing so multi-project emit stays stable.
        if (registry == null && GradleBomDraftMapper.isBom(primary)) {
            return GradleBomDraftMapper.map(primary, notes);
        }
        DraftDependencies dependencies = new DraftDependencies(notes);
        new GradleDependencySectionMapper(dependencies, registry, notes).map(primary.dependencies());
        addCatalogNotes(aliases, notes);
        addRepositoryNotes(primary.repositories(), notes);

        Optional<String> group = defaults.group(
                primary.group().orElse(""), () -> PLACEHOLDER_GROUP);
        Optional<String> version = defaults.version(
                primary.version().orElse(""), () -> PLACEHOLDER_VERSION);
        Optional<Integer> javaRelease = defaults.javaRelease(
                JavaVersionNotation.featureRelease(primary.javaVersion()),
                () -> notes.add(MavenInspectionMapper.unreadableJavaNote(primary.javaVersion())));
        addCoordinatePlaceholderNotes(primary, notes);
        AuthoredManifest manifest = DraftManifests.project(
                DraftManifests.identity(
                        primary.name(),
                        group,
                        version,
                        javaRelease,
                        notes),
                DraftManifests.metadata(
                        primary.mainClass().filter(value -> !value.isBlank()), notes),
                dependencies,
                Optional.empty(),
                InspectionBuildSettingsMapper.fromRoots(
                        primary.sourceRoots(),
                        primary.testSourceRoots(),
                        primary.groovyTestSourceRoots(),
                        List.of(),
                        List.of(),
                        notes),
                Optional.empty(),
                AuthoredPackaging.empty());
        return new DraftZoltToml(manifest, notes);
    }

    static String emittedGroup(GradleProjectInspection project) {
        return project.group().filter(value -> !value.isBlank()).orElse(PLACEHOLDER_GROUP);
    }

    static String emittedVersion(GradleProjectInspection project) {
        return project.version().filter(value -> !value.isBlank()).orElse(PLACEHOLDER_VERSION);
    }

    /** Adds the group/version placeholder review notes when the static audit could not read them. */
    static void addCoordinatePlaceholderNotes(GradleProjectInspection project, List<String> notes) {
        boolean groupMissing = project.group().filter(value -> !value.isBlank()).isEmpty();
        boolean versionMissing = project.version().filter(value -> !value.isBlank()).isEmpty();
        if (groupMissing && versionMissing) {
            notes.add(
                    "Project group and version are placeholders; the static Gradle audit could not read them."
                            + " Set `group` and `version` to your real coordinates.");
        } else if (groupMissing) {
            notes.add(
                    "Project group is a placeholder; the static Gradle audit could not read it."
                            + " Set `group` to your real coordinate.");
        } else if (versionMissing) {
            notes.add(
                    "Project version is a placeholder; the static Gradle audit could not read it."
                            + " Set `version` to your real coordinate.");
        }
    }

    private static void addCatalogNotes(List<GradleVersionCatalogAlias> aliases, List<String> notes) {
        for (GradleVersionCatalogAlias alias : aliases) {
            if (alias.coordinate() == null || alias.coordinate().isBlank()) {
                notes.add(
                        "Version-catalog alias `" + alias.alias() + "` has no coordinate in the audit;"
                                + " resolve it from libs.versions.toml before use.");
            }
        }
    }

    private static void addRepositoryNotes(List<GradleRepositoryInspection> repositories, List<String> notes) {
        for (GradleRepositoryInspection repository : repositories) {
            if (repository.url() == null || repository.url().isBlank()) {
                continue;
            }
            if (repository.url().contains("repo.maven.apache.org") || "mavenCentral".equals(repository.kind())) {
                continue;
            }
            notes.add(
                    "Custom Gradle repository `" + repository.url() + "` (" + repository.kind()
                            + ") was declared; Zolt defaults to Maven Central only. Add it under"
                            + " [repositories] if your build needs it.");
        }
    }

    static String coordinateOf(String resolved) {
        String[] parts = resolved.split(":");
        if (parts.length >= 2) {
            return parts[0] + ":" + parts[1];
        }
        return resolved;
    }

    static String versionOf(String resolved) {
        String[] parts = resolved.split(":");
        if (parts.length >= 3 && !parts[2].isBlank()) {
            return parts[2];
        }
        return null;
    }

    private static String path(String path) {
        return path.replace('\\', '/');
    }
}
