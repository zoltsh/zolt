package sh.zolt.manifest.adapter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.ProjectLocalDomains;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveProject;
import sh.zolt.manifest.effective.EffectiveSharedConfiguration;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.manifest.effective.EffectiveWorkspaceDependencyEdge;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.CoverageSettings;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.PublicationMetadata;

/**
 * Adapts a final-language {@link EffectiveManifest} to the legacy {@link ProjectConfig} the existing
 * build engine consumes.
 *
 * <p>This is the one boundary between the final manifest language and the pre-cut engine model. It
 * reads only composed effective values, never manifest source text, so it stays a pure model-to-model
 * projection with no second parser. The legacy parser continues to parse only the legacy language;
 * this adapter serves only the final language.
 *
 * <p>Legacy semantics that the final language abolishes are reported as their legacy "absent" value
 * rather than being reconstructed:
 *
 * <ul>
 *   <li>{@code [compiler].release} — design §7.2 gives the release target solely to {@code project.java};
 *   <li>{@code [package.metadata]} — design §14.4 sources publication metadata from {@code [project]},
 *       so the POM display {@code name} and the flat {@code developers} name array are gone;
 *   <li>{@code [project].java} on a BOM — design §12.6 forbids a BOM Java release;
 *   <li>{@code [framework.quarkus].enabled} — design §12.4 derives Quarkus from {@code package.mode};
 *   <li>{@code cacheSalt} on exec steps — design §13.7 requires {@code cache = "none"} with secrets;
 *   <li>{@code kind} on dependency constraints — design §9.10 leaves strict as the sole semantic.
 * </ul>
 */
public final class EffectiveProjectConfigAdapter {
    /**
     * Adapts one standalone project or BOM. A standalone manifest cannot declare
     * {@code workspace = true}, so no member index is required.
     */
    public ProjectConfig adapt(EffectiveManifest manifest) {
        return adapt(manifest, Map.of());
    }

    /**
     * Adapts one workspace member.
     *
     * @param manifest the member's effective manifest
     * @param workspacePaths provider member path per {@code workspace = true} coordinate, resolved by
     *     {@link EffectiveWorkspace#graph()}
     */
    public ProjectConfig adapt(
            EffectiveManifest manifest,
            Map<DependencyCoordinate, String> workspacePaths) {
        Objects.requireNonNull(manifest, "Effective manifest must not be null.");
        Objects.requireNonNull(workspacePaths, "Workspace member paths must not be null.");
        EffectiveProject project = manifest.project();
        EffectiveSharedConfiguration shared = project.shared();
        ProjectLocalDomains local = project.local();

        Map<String, DependencyMetadata> seeded = new LinkedHashMap<>();
        Map<String, String> platforms = platforms(shared, seeded);
        ProjectConfigDependencyLanes lanes = ProjectConfigDependencyLanes.adapt(
                local.dependencies(), shared.versions(), workspacePaths, seeded);

        Optional<AuthoredBuild> build = local.build();
        String outputRoot = ProjectConfigBuild.outputRoot(build);
        List<GeneratedSourceStep> generatedMain = ProjectConfigGenerated.main(
                local.generated(), outputRoot, shared.versions());
        List<GeneratedSourceStep> generatedTest = ProjectConfigGenerated.test(
                local.generated(), outputRoot, shared.versions());
        BuildSettings buildSettings = ProjectConfigBuild.build(
                build, local.resources(), local.tests(), generatedMain, generatedTest);
        CompilerSettings compilerSettings = ProjectConfigBuild.compiler(local.compiler(), outputRoot);

        ProjectMetadata metadata = ProjectConfigIdentity.project(project.identity(), local.metadata());
        PublicationMetadata publication =
                ProjectConfigIdentity.publication(project.identity(), local.metadata());

        return new ProjectConfig(
                metadata,
                Map.of(),
                ProjectConfigRepositories.settings(shared.repositories()),
                ProjectConfigRepositories.credentials(shared.credentials()),
                ProjectConfigVersions.aliases(shared.versions()),
                platforms,
                lanes.versioned(DependencyLane.API),
                lanes.managed(DependencyLane.API),
                lanes.workspace(DependencyLane.API),
                lanes.versioned(DependencyLane.IMPLEMENTATION),
                lanes.managed(DependencyLane.IMPLEMENTATION),
                lanes.workspace(DependencyLane.IMPLEMENTATION),
                lanes.versioned(DependencyLane.RUNTIME),
                lanes.managed(DependencyLane.RUNTIME),
                lanes.versioned(DependencyLane.PROVIDED),
                lanes.managed(DependencyLane.PROVIDED),
                lanes.versioned(DependencyLane.DEV),
                lanes.managed(DependencyLane.DEV),
                lanes.versioned(DependencyLane.TEST),
                lanes.managed(DependencyLane.TEST),
                lanes.workspace(DependencyLane.TEST),
                lanes.versioned(DependencyLane.PROCESSOR),
                lanes.managed(DependencyLane.PROCESSOR),
                lanes.workspace(DependencyLane.PROCESSOR),
                lanes.versioned(DependencyLane.TEST_PROCESSOR),
                lanes.managed(DependencyLane.TEST_PROCESSOR),
                lanes.workspace(DependencyLane.TEST_PROCESSOR),
                ProjectConfigPolicy.policy(
                        local.dependencyPolicy(), local.dependencyConstraints(), shared.versions()),
                buildSettings,
                ProjectConfigPackaging.nativeSettings(
                        local.packaging(), metadata.name(), outputRoot),
                compilerSettings,
                ProjectConfigPackaging.packageSettings(
                        local.packaging(), publication, shared.versions()),
                ProjectConfigPackaging.framework(local.packaging()),
                lanes.metadata());
    }

    /** The effective {@code [coverage]} floors, after workspace inheritance, as legacy settings. */
    public CoverageSettings coverage(EffectiveManifest manifest) {
        Objects.requireNonNull(manifest, "Effective manifest must not be null.");
        return ProjectConfigCoverage.effective(manifest.project().shared().coverage());
    }

    /**
     * The {@code workspace = true} provider paths for one member, taken from the composed workspace
     * graph.
     */
    public static Map<DependencyCoordinate, String> workspacePaths(
            EffectiveWorkspace workspace,
            WorkspaceMemberPath member) {
        Objects.requireNonNull(workspace, "Effective workspace must not be null.");
        Objects.requireNonNull(member, "Workspace member path must not be null.");
        Map<DependencyCoordinate, String> paths = new LinkedHashMap<>();
        for (EffectiveWorkspaceDependencyEdge edge : workspace.graph().workspaceDependencies()) {
            if (edge.consumer().equals(member)) {
                paths.put(edge.declaration().coordinate(), edge.provider().value());
            }
        }
        return Map.copyOf(paths);
    }

    private static Map<String, String> platforms(
            EffectiveSharedConfiguration shared,
            Map<String, DependencyMetadata> metadata) {
        Map<String, String> platforms = new LinkedHashMap<>();
        for (Map.Entry<DependencyCoordinate, EffectiveValue<PlatformSelector>> entry
                : shared.platforms().entrySet()) {
            String coordinate = entry.getKey().value();
            PlatformSelector selector = entry.getValue().value();
            String version = ProjectConfigVersions.resolve(
                    selector, shared.versions(), "[platforms] `" + coordinate + "`");
            platforms.put(coordinate, version);
            String reference = ProjectConfigVersions.reference(selector);
            if (reference != null) {
                metadata.put(
                        DependencyMetadata.key("platforms", coordinate),
                        new DependencyMetadata(
                                "platforms",
                                coordinate,
                                version,
                                reference,
                                false,
                                null,
                                false,
                                false,
                                List.of()));
            }
        }
        return Map.copyOf(platforms);
    }

}
