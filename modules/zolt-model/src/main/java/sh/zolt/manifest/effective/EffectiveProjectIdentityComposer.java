package sh.zolt.manifest.effective;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.manifest.authored.AuthoredProjectIdentity;
import sh.zolt.manifest.authored.AuthoredWorkspaceProjectDefaults;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/** Applies the closed workspace project-default set with per-field provenance. */
final class EffectiveProjectIdentityComposer {
    EffectiveProjectIdentity compose(
            AuthoredProjectIdentity project,
            String projectManifestPath,
            Optional<WorkspaceDefaults> defaults,
            boolean bom) {
        AuthoredProjectIdentity authored = Objects.requireNonNull(
                project, "Authored project identity is required.");
        String projectPath = requireManifestPath(
                projectManifestPath, "Project manifest path is required.");
        Optional<WorkspaceDefaults> inherited = Objects.requireNonNull(
                defaults, "Workspace project defaults must not be null.");
        if (bom && authored.javaRelease().isPresent()) {
            throw new IllegalArgumentException(
                    "An effective BOM cannot consume an authored project Java release.");
        }

        EffectiveValue<ProjectVersion> version = value(
                        authored.version(), projectPath, inherited,
                        AuthoredWorkspaceProjectDefaults::version, "version")
                .orElseThrow(() -> missing("version"));
        EffectiveValue<ProjectGroup> group = value(
                        authored.group(), projectPath, inherited,
                        AuthoredWorkspaceProjectDefaults::group, "group")
                .orElseThrow(() -> missing("group"));
        Optional<EffectiveValue<JavaFeatureRelease>> javaRelease = bom
                ? Optional.empty()
                : value(
                        authored.javaRelease(), projectPath, inherited,
                        AuthoredWorkspaceProjectDefaults::javaRelease, "java");
        if (!bom && javaRelease.isEmpty()) {
            throw missing("java");
        }
        Optional<EffectiveValue<ProjectLicense>> license = value(
                authored.license(), projectPath, inherited,
                AuthoredWorkspaceProjectDefaults::license, "license");

        return new EffectiveProjectIdentity(
                EffectiveValue.authored(
                        authored.name(), source(projectPath, "project", "name")),
                version,
                group,
                javaRelease,
                license);
    }

    private static <T> Optional<EffectiveValue<T>> value(
            Optional<T> authored,
            String projectManifestPath,
            Optional<WorkspaceDefaults> defaults,
            Function<AuthoredWorkspaceProjectDefaults, Optional<T>> inherited,
            String field) {
        if (authored.isPresent()) {
            return Optional.of(EffectiveValue.authored(
                    authored.orElseThrow(), source(projectManifestPath, "project", field)));
        }
        return defaults.flatMap(workspace -> inherited.apply(workspace.values())
                .map(value -> EffectiveValue.inherited(
                        value,
                        source(workspace.manifestPath(), "workspace", "project", field))));
    }

    private static IllegalArgumentException missing(String field) {
        return new IllegalArgumentException(
                "Effective project " + field + " requires `project." + field
                        + "` or `workspace.project." + field + "`.");
    }

    private static String requireManifestPath(String path, String message) {
        Objects.requireNonNull(path, message);
        return source(path, "manifest").manifestPath();
    }

    private static ManifestSource source(String manifestPath, String... fieldPath) {
        return new ManifestSource(manifestPath, List.of(fieldPath));
    }

    record WorkspaceDefaults(
            AuthoredWorkspaceProjectDefaults values,
            String manifestPath) {
        WorkspaceDefaults {
            values = Objects.requireNonNull(
                    values, "Authored workspace project defaults are required.");
            manifestPath = requireManifestPath(
                    manifestPath, "Workspace manifest path is required.");
        }
    }
}
