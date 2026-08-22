package sh.zolt.cli.command.publish;

import static sh.zolt.dependency.DependencyLane.IMPLEMENTATION;
import static sh.zolt.dependency.DependencyScope.COMPILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.sbom.LockSbomAssembler;
import sh.zolt.sbom.SbomComponent;
import sh.zolt.sbom.SbomDependency;
import sh.zolt.sbom.SbomModel;
import sh.zolt.sbom.SbomScopeSelection;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-member publish SBOM is materially complete. Driving the real production seam
 * ({@link WorkspaceMemberSbomLockProjection} feeding {@link PublishSbomArtifactGenerator#memberGenerator})
 * over an aggregated lock produces a CycloneDX that carries the member's full transitive closure,
 * artifact SHA-256 hashes, and the {@code root -> direct}, {@code direct -> transitive}, and
 * {@code root -> sibling} edges — the supply-chain evidence the POM-shaped projection silently drops.
 * Scope filtering still holds: a test-scope dependency the member declares never reaches the SBOM.
 */
final class WorkspaceMemberSbomGeneratorClosureTest {
    private static final String HTTP = "acme-http";
    private static final String CORE = "acme-core";
    private static final String TOOL_VERSION = "0.1.0-TEST";
    private static final String SHA_DATABIND =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SHA_CORE =
            "2222222222222222222222222222222222222222222222222222222222222222";
    private static final String SHA_GUAVA =
            "3333333333333333333333333333333333333333333333333333333333333333";
    private static final String SHA_JUNIT =
            "4444444444444444444444444444444444444444444444444444444444444444";
    private static final String SHA_FIXTURE_LINUX =
            "5555555555555555555555555555555555555555555555555555555555555555";
    private static final String SHA_FIXTURE_PLAIN =
            "6666666666666666666666666666666666666666666666666666666666666666";

    private final WorkspaceMemberPolicyResolver resolver = new WorkspaceMemberPolicyResolver();

    @TempDir
    private Path memberDir;

    @Test
    void producesCompleteMemberCycloneDxThroughTheRealGeneratorSeam() throws IOException {
        // acme-http: one direct external (jackson-databind -> jackson-core), one workspace sibling
        // (acme-core), and a test-only dependency that must not surface in the published SBOM.
        ProjectConfig config = memberConfig(
                "acme-http",
                Map.of("com.fasterxml.jackson.core:jackson-databind", "2.19.2"),
                Map.of("com.acme:acme-core", "acme-core"),
                Map.of("org.junit.jupiter:junit-jupiter", "5.11.4"));
        Workspace workspace = workspaceOf(
                member("acme-http", config),
                member("acme-core", memberConfig("acme-core", Map.of(), Map.of(), Map.of())));

        ZoltLockfile aggregated = lock(
                List.of(
                        workspacePackage("com.acme", CORE, "1.0.0", List.of(HTTP)),
                        external("com.fasterxml.jackson.core", "jackson-databind", "2.19.2", COMPILE,
                                SHA_DATABIND, List.of("com.fasterxml.jackson.core:jackson-core:2.19.2")),
                        external("com.fasterxml.jackson.core", "jackson-core", "2.19.2", COMPILE, SHA_CORE, List.of()),
                        external("org.junit.jupiter", "junit-jupiter", "5.11.4",
                                DependencyScope.TEST, SHA_JUNIT, List.of())),
                List.of(
                        root(HTTP, "com.acme:acme-core:1.0.0", IMPLEMENTATION, COMPILE),
                        root(HTTP, "com.fasterxml.jackson.core:jackson-databind:2.19.2", IMPLEMENTATION, COMPILE),
                        root(HTTP, "org.junit.jupiter:junit-jupiter:5.11.4",
                                DependencyLane.TEST, DependencyScope.TEST)));

        ZoltLockfile sbomLock =
                new WorkspaceMemberSbomLockProjection()
                        .project("acme-http", config, aggregated, workspace, resolver);

        // The exact seam publish --workspace --sbom uses: project -> memberGenerator -> written file.
        Path sbomFile = new PublishSbomArtifactGenerator()
                .memberGenerator(true, TOOL_VERSION)
                .generate(memberDir, config, sbomLock)
                .orElseThrow();
        String json = Files.readString(sbomFile);

        // Produced file is a CycloneDX carrying BOTH externals and the sibling as components.
        assertTrue(json.contains("\"bomFormat\": \"CycloneDX\""), json);
        assertTrue(json.contains("jackson-databind"), json);
        assertTrue(json.contains("jackson-core"), json);
        assertTrue(json.contains("acme-core"), json);
        // Artifact SHA-256 hashes are present for both externals (the POM projection dropped these).
        assertTrue(json.contains("\"SHA-256\""), json);
        assertTrue(json.contains(SHA_DATABIND), json);
        assertTrue(json.contains(SHA_CORE), json);
        // Scope filtering: the test-only dependency and its hash never reach the published SBOM.
        assertFalse(json.contains("junit"), json);
        assertFalse(json.contains(SHA_JUNIT), json);

        // Edges: assert precisely on the model the writer serializes. memberGenerator assembles with
        // requiredOnly(), so this is the same graph the file above encodes.
        SbomModel model = new LockSbomAssembler().assemble(
                config, sbomLock, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION);
        String rootRef = model.metadataComponent().bomRef();
        String databindRef = refByName(model, "jackson-databind");
        String coreRef = refByName(model, "jackson-core");
        String siblingRef = refByName(model, "acme-core");

        assertTrue(dependsOn(model, rootRef).contains(databindRef), "root -> direct external");
        assertTrue(dependsOn(model, rootRef).contains(siblingRef), "root -> workspace sibling");
        assertTrue(dependsOn(model, databindRef).contains(coreRef), "direct external -> transitive external");
        // Those bom-refs are exactly the ones serialized into the produced file.
        assertTrue(json.contains(databindRef), databindRef);
        assertTrue(json.contains(coreRef), coreRef);
        assertTrue(json.contains(siblingRef), siblingRef);
    }

    @Test
    void carriesTheSiblingOwnedExternalClosureThroughTheRealGeneratorSeam() throws IOException {
        // The review's exact scenario, end-to-end through the generator seam:
        //   acme-http -> acme-core (workspace dependency) -> guava (acme-core's external dependency).
        // acme-core's aggregated lock entry carries no edges, so a plain BFS would stop at the sibling and
        // drop guava; the workspace-aware projection must synthesize acme-core -> guava and carry guava in.
        ProjectConfig config =
                memberConfig("acme-http", Map.of(), Map.of("com.acme:acme-core", "acme-core"), Map.of());
        ProjectConfig coreConfig =
                memberConfig("acme-core", Map.of("com.google.guava:guava", "33.0.0-jre"), Map.of(), Map.of());
        Workspace workspace = workspaceOf(member("acme-http", config), member("acme-core", coreConfig));

        ZoltLockfile aggregated = lock(
                List.of(
                        workspacePackage("com.acme", CORE, "1.0.0", List.of(HTTP)),
                        externalOwnedBy("com.google.guava", "guava", "33.0.0-jre",
                                COMPILE, SHA_GUAVA, List.of(), List.of(CORE))),
                List.of(
                        root(HTTP, "com.acme:acme-core:1.0.0", IMPLEMENTATION, COMPILE),
                        root(CORE, "com.google.guava:guava:33.0.0-jre", IMPLEMENTATION, COMPILE)));

        ZoltLockfile sbomLock =
                new WorkspaceMemberSbomLockProjection()
                        .project("acme-http", config, aggregated, workspace, resolver);

        Path sbomFile = new PublishSbomArtifactGenerator()
                .memberGenerator(true, TOOL_VERSION)
                .generate(memberDir, config, sbomLock)
                .orElseThrow();
        String json = Files.readString(sbomFile);

        // The produced CycloneDX carries the sibling AND its external, guava's SHA-256 included.
        assertTrue(json.contains("\"bomFormat\": \"CycloneDX\""), json);
        assertTrue(json.contains("acme-core"), json);
        assertTrue(json.contains("guava"), json);
        assertTrue(json.contains(SHA_GUAVA), json);

        SbomModel model = new LockSbomAssembler().assemble(
                config, sbomLock, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION);
        String rootRef = model.metadataComponent().bomRef();
        String coreRef = refByName(model, "acme-core");
        String guavaRef = refByName(model, "guava");

        // All THREE graph nodes are present: root acme-http (metadata component) + acme-core + guava.
        assertEquals(2, model.components().size(), "acme-core + guava are the two library components");
        // BOTH edges render: root -> core (the member's declared sibling) and core -> guava (synthesized).
        assertTrue(dependsOn(model, rootRef).contains(coreRef), "root -> workspace sibling");
        assertTrue(dependsOn(model, coreRef).contains(guavaRef), "workspace sibling -> its external");
        // guava is the sibling's transitive, never a root-direct of acme-http.
        assertFalse(dependsOn(model, rootRef).contains(guavaRef), "guava is not root-direct");
        // The serialized file encodes exactly those bom-refs.
        assertTrue(json.contains(coreRef), coreRef);
        assertTrue(json.contains(guavaRef), guavaRef);
    }

    @Test
    void siblingEdgeUsesItsDeclaredClassifierWhenTheSiblingOwnsBothVariants() throws IOException {
        String fixture = "com.example:fixture";
        ProjectConfig config =
                memberConfig("acme-http", Map.of(), Map.of("com.acme:acme-core", "acme-core"), Map.of());
        ProjectConfig coreConfig = memberConfig(
                        "acme-core",
                        Map.of(fixture, "1.0.0", "com.example:helper", "1.0.0"),
                        Map.of(),
                        Map.of())
                .withDependencyMetadata(Map.of(
                        DependencyMetadata.key("dependencies", fixture),
                        new DependencyMetadata(
                                "dependencies",
                                fixture,
                                null,
                                null,
                                false,
                                null,
                                false,
                                false,
                                List.of(),
                                "linux-x86_64",
                                null)));
        Workspace workspace = workspaceOf(member("acme-http", config), member("acme-core", coreConfig));

        ZoltLockfile aggregated = lock(
                List.of(
                        workspacePackage("com.acme", CORE, "1.0.0", List.of(HTTP)),
                        // Order the plain transitive first to prove same-member attribution cannot decide.
                        externalOwnedBy("com.example", "fixture", "1.0.0", COMPILE,
                                SHA_FIXTURE_PLAIN, List.of(), List.of(CORE)),
                        externalOwnedBy("com.example", "helper", "1.0.0", COMPILE,
                                SHA_GUAVA, List.of("com.example:fixture:1.0.0"), List.of(CORE)),
                        variant("com.example", "fixture", "1.0.0", COMPILE,
                                Optional.of("linux-x86_64"), SHA_FIXTURE_LINUX, List.of(), List.of(CORE))),
                List.of(
                        root(HTTP, "com.acme:acme-core:1.0.0", IMPLEMENTATION, COMPILE),
                        root(CORE, fixture + ":1.0.0", IMPLEMENTATION, COMPILE,
                                new LockArtifactVariant("jar", Optional.of("linux-x86_64"))),
                        root(CORE, "com.example:helper:1.0.0", IMPLEMENTATION, COMPILE)));

        ZoltLockfile sbomLock =
                new WorkspaceMemberSbomLockProjection()
                        .project("acme-http", config, aggregated, workspace, resolver);
        SbomModel model = new LockSbomAssembler().assemble(
                config, sbomLock, SbomScopeSelection.requiredOnly(), Optional.empty(), TOOL_VERSION);

        String coreRef = refByName(model, "acme-core");
        List<SbomComponent> fixtures = model.components().stream()
                .filter(component -> component.name().equals("fixture"))
                .toList();
        assertEquals(2, fixtures.size(), "both the declared classified artifact and plain transitive survive");
        SbomComponent linux = fixtures.stream()
                .filter(component -> component.purl().contains("classifier=linux-x86_64"))
                .findFirst()
                .orElseThrow();
        SbomComponent plain = fixtures.stream()
                .filter(component -> !component.purl().contains("classifier="))
                .findFirst()
                .orElseThrow();

        assertTrue(dependsOn(model, coreRef).contains(linux.bomRef()), "sibling -> declared linux variant");
        assertFalse(dependsOn(model, coreRef).contains(plain.bomRef()), "sibling must not point at plain transitive");
        assertTrue(
                linux.hashes().stream().anyMatch(hash -> hash.content().equals(SHA_FIXTURE_LINUX)),
                "the classified component carries its own artifact hash");
    }

    private static ZoltLockfile lock(List<LockPackage> packages, List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                List.of(),
                roots);
    }

    /** One member-qualified v7 authored root: the lane stays independent of the resolved scope. */
    private static LockDependencyRoot root(
            String member,
            String coordinate,
            DependencyLane lane,
            DependencyScope scope) {
        return root(member, coordinate, lane, scope, LockArtifactVariant.defaultVariant());
    }

    private static LockDependencyRoot root(
            String member,
            String coordinate,
            DependencyLane lane,
            DependencyScope scope,
            LockArtifactVariant variant) {
        String[] parts = coordinate.split(":");
        return new LockDependencyRoot(
                member,
                new PackageId(parts[0], parts[1]),
                parts[2],
                variant,
                lane,
                Optional.of(scope),
                false,
                false);
    }

    private static ProjectConfig memberConfig(
            String name,
            Map<String, String> dependencies,
            Map<String, String> workspaceDependencies,
            Map<String, String> testDependencies) {
        return ProjectConfigs.withWorkspaceDependencySections(
                new ProjectMetadata(name, "1.0.0", "com.acme", "21", Optional.empty()),
                Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                Map.of(),
                dependencies,
                Set.of(),
                workspaceDependencies,
                testDependencies,
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                NativeSettings.defaults());
    }

    private static WorkspaceMember member(String path, ProjectConfig config) {
        return new WorkspaceMember(path, Path.of("/ws").resolve(path), config);
    }

    private static Workspace workspaceOf(WorkspaceMember... members) {
        List<String> paths = new ArrayList<>();
        for (WorkspaceMember member : members) {
            paths.add(member.path());
        }
        return new Workspace(
                Path.of("/ws"),
                Path.of("/ws/zolt.toml"),
                new WorkspaceConfig("acme", paths, List.of(), Map.of(), Map.of()),
                List.of(members));
    }

    private static String refByName(SbomModel model, String name) {
        return model.components().stream()
                .filter(component -> component.name().equals(name))
                .map(SbomComponent::bomRef)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no component named " + name));
    }

    private static List<String> dependsOn(SbomModel model, String ref) {
        return model.dependencies().stream()
                .filter(dependency -> dependency.ref().equals(ref))
                .map(SbomDependency::dependsOn)
                .findFirst()
                .orElse(List.of());
    }

    private static LockPackage external(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            String jarSha256,
            List<String> dependencies) {
        return externalOwnedBy(group, artifact, version, scope, jarSha256, dependencies, List.of(HTTP));
    }

    private static LockPackage externalOwnedBy(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            String jarSha256,
            List<String> dependencies,
            List<String> members) {
        return variant(group, artifact, version, scope, Optional.empty(), jarSha256, dependencies, members);
    }

    private static LockPackage variant(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            Optional<String> classifier,
            String jarSha256,
            List<String> dependencies,
            List<String> members) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "maven-central",
                scope,
                false,
                Optional.of(base + classifier.map(value -> "-" + value).orElse("") + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of(jarSha256),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                members);
    }

    private static LockPackage workspacePackage(
            String group,
            String artifact,
            String version,
            List<String> members) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "workspace",
                COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(artifact),
                Optional.of("target/classes"),
                List.of(),
                members);
    }
}
