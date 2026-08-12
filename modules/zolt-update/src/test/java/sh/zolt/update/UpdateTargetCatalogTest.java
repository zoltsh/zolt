package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class UpdateTargetCatalogTest {
    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();

    @Test
    void collectsMutableSurfacesWithCanonicalPathsAndAliasOwnership() {
        List<UpdateTarget> targets = catalog.collect(config("""
                [versions]
                shared = "1.0.0"

                [dependencies]
                "com.example:aliased" = { versionRef = "shared" }
                "com.example:direct" = "2.0.0"

                [annotationProcessors]
                "com.example:processor" = "3.0.0"

                [platforms]
                "com.example:bom" = "4.0.0"

                [dependencyConstraints]
                "com.example:constrained" = { version = "5.0.0", kind = "strict", reason = "contract" }
                """), "apps/api/zolt.toml", "zolt.lock");

        assertEquals(List.of(
                OutdatedSurface.VERSION_ALIAS,
                OutdatedSurface.DEPENDENCY,
                OutdatedSurface.ANNOTATION_PROCESSOR,
                OutdatedSurface.PLATFORM,
                OutdatedSurface.DEPENDENCY_CONSTRAINT), targets.stream().map(UpdateTarget::surface).toList());
        assertTrue(targets.stream().allMatch(UpdateTarget::updateable));
        assertTrue(targets.stream().allMatch(target -> target.manifestPath().equals("apps/api/zolt.toml")));
        assertTrue(targets.stream().allMatch(target -> target.lockfilePath().equals("zolt.lock")));
        UpdateTarget alias = targets.getFirst();
        assertEquals("shared", alias.identifier());
        assertEquals(List.of("[dependencies].com.example:aliased"), alias.governs());
        assertFalse(targets.stream().anyMatch(target -> target.identifier().equals("com.example:aliased")));
    }

    @Test
    void collectsOnlyLiteralPlatformsFromWorkspaceRootPolicy() {
        WorkspaceConfig workspace = new WorkspaceConfig(
                "demo",
                List.of("apps/api"),
                List.of(),
                Map.of(),
                Map.of("org.junit:junit-bom", "5.10.2"));

        UpdateTarget target = catalog.collect(workspace, "zolt.toml", "zolt.lock").getFirst();

        assertEquals(OutdatedSurface.PLATFORM, target.surface());
        assertEquals("org.junit:junit-bom", target.identifier());
        assertEquals("[platforms]", target.section());
        assertEquals("zolt.toml", target.manifestPath());
        assertEquals("zolt.lock", target.lockfilePath());
        assertTrue(target.updateable());
        assertEquals(target, catalog.require(workspace, "zolt.toml", "zolt.lock", target.targetId()));
    }

    @Test
    void preservesEveryDependencyAndProcessorSectionInIdentity() {
        List<UpdateTarget> targets = catalog.collect(config("""
                [dependencies]
                "com.example:main" = "1.0.0"
                [api.dependencies]
                "com.example:api" = "1.0.0"
                [runtime.dependencies]
                "com.example:runtime" = "1.0.0"
                [provided.dependencies]
                "com.example:provided" = "1.0.0"
                [dev.dependencies]
                "com.example:dev" = "1.0.0"
                [test.dependencies]
                "com.example:test" = "1.0.0"
                [annotationProcessors]
                "com.example:processor" = "1.0.0"
                [test.annotationProcessors]
                "com.example:test-processor" = "1.0.0"
                """), "zolt.toml", "zolt.lock");

        assertEquals(List.of(
                "[dependencies]",
                "[api.dependencies]",
                "[runtime.dependencies]",
                "[provided.dependencies]",
                "[dev.dependencies]",
                "[test.dependencies]",
                "[annotationProcessors]",
                "[test.annotationProcessors]"), targets.stream().map(UpdateTarget::section).toList());
    }

    @Test
    void omitsSnapshotLiteralsAndKeepsIdentityStableAcrossVersions() {
        ProjectConfig before = config("""
                [dependencies]
                "com.example:lib" = "1.0.0"
                "com.example:snapshot" = "2.0.0-SNAPSHOT"
                "com.example:workspace" = { workspace = "modules/local" }
                """);
        ProjectConfig after = config("""
                [dependencies]
                "com.example:lib" = "1.1.0"
                "com.example:snapshot" = "2.0.0-SNAPSHOT"
                "com.example:workspace" = { workspace = "modules/local" }
                """);

        UpdateTarget first = catalog.collect(before, "zolt.toml", "zolt.lock").getFirst();
        UpdateTarget second = catalog.collect(after, "zolt.toml", "zolt.lock").getFirst();

        assertEquals(1, catalog.collect(before, "zolt.toml", "zolt.lock").size());
        assertEquals(first.targetId(), second.targetId());
        assertEquals("1.0.0", first.currentVersion());
        assertEquals("1.1.0", second.currentVersion());
    }

    @Test
    void reportsGeneratedToolLiteralsAsBlockedTargets() {
        List<UpdateTarget> targets = catalog.collect(config("""
                [generated.openapiTool]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """), "zolt.toml", "zolt.lock");

        UpdateTarget target = targets.getFirst();
        assertEquals(OutdatedSurface.OPENAPI_TOOL, target.surface());
        assertFalse(target.updateable());
        assertTrue(target.updateBlocker().orElseThrow().contains("generated-tool"));
    }

    @Test
    void reportsExecAndProtobufToolLiteralsAsBlockedTargets() {
        UpdateTarget exec = catalog.collect(config("""
                [generated.execTools.codegen]
                runner = "jvm"
                coordinates = [{ coordinate = "com.example:codegen", version = "1.2.3" }]
                mainClass = "com.example.Codegen"

                [generated.main.codegen]
                kind = "exec"
                tool = "codegen"
                args = []
                inputs = ["src/main/schema.txt"]
                output = "target/generated/sources/codegen"
                produces = "java-sources"
                """), "zolt.toml", "zolt.lock").getFirst();
        List<UpdateTarget> protobuf = catalog.collect(config("""
                [generated.protobufTool]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.28.3"
                grpcPluginCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcPluginVersion = "1.68.1"

                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                output = "target/generated/sources/protobuf"
                inputs = ["src/main/proto/greeter.proto"]
                javaPackage = "com.example.greeter"
                grpc = true
                """), "zolt.toml", "zolt.lock");

        assertEquals(OutdatedSurface.EXEC_TOOL_COORDINATE, exec.surface());
        assertFalse(exec.updateable());
        assertEquals(List.of(OutdatedSurface.PROTOBUF_TOOL, OutdatedSurface.PROTOBUF_TOOL),
                protobuf.stream().map(UpdateTarget::surface).toList());
        assertTrue(protobuf.stream().noneMatch(UpdateTarget::updateable));
    }

    @Test
    void requireFindsOnlyTheExactCurrentTarget() {
        ProjectConfig config = config("""
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        UpdateTarget target = catalog.collect(config, "zolt.toml", "zolt.lock").getFirst();

        assertEquals(target, catalog.require(config, "zolt.toml", "zolt.lock", target.targetId()));
        assertThrows(IllegalArgumentException.class, () -> catalog.require(
                config,
                "zolt.toml",
                "zolt.lock",
                UpdateTargetId.create(
                        "zolt.toml",
                        OutdatedSurface.DEPENDENCY,
                        "[test.dependencies]",
                        "com.example:lib")));
    }

    @Test
    void collectionOrderAndIdsAreDeterministic() {
        ProjectConfig config = config("""
                [dependencies]
                "com.example:zeta" = "1.0.0"
                "com.example:alpha" = "1.0.0"
                """);

        List<UpdateTarget> first = catalog.collect(config, "zolt.toml", "zolt.lock");
        List<UpdateTarget> second = catalog.collect(config, "zolt.toml", "zolt.lock");

        assertEquals(first, second);
        assertEquals(List.of("com.example:alpha", "com.example:zeta"),
                first.stream().map(UpdateTarget::identifier).toList());
    }

    @Test
    void lockfileAndDescriptiveStateDoNotAffectIdentity() {
        ProjectConfig config = config("""
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);

        UpdateTarget rootLock = catalog.collect(config, "apps/api/zolt.toml", "zolt.lock").getFirst();
        UpdateTarget nestedLock = catalog.collect(
                config,
                "apps/api/zolt.toml",
                "locks/dependencies.lock").getFirst();

        assertEquals(rootLock.targetId(), nestedLock.targetId());
        assertEquals("zolt.lock", rootLock.lockfilePath());
        assertEquals("locks/dependencies.lock", nestedLock.lockfilePath());
    }

    @Test
    void duplicateIdsAreAnInternalFailure() {
        UpdateTargetCatalog.Entry entry = catalog.entries(
                config("""
                        [dependencies]
                        "com.example:lib" = "1.0.0"
                        """),
                "zolt.toml",
                "zolt.lock").getFirst();
        Map<UpdateTargetId, UpdateTargetCatalog.Entry> entries = new LinkedHashMap<>();

        UpdateTargetCatalog.addUnique(entries, entry);

        assertThrows(IllegalStateException.class, () -> UpdateTargetCatalog.addUnique(entries, entry));
    }

    private static ProjectConfig config(String body) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                %s
                """.formatted(body));
    }
}
