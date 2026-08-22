package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.ProjectConfig;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class UpdateTargetCatalogTest {
    private static final ManifestProjectConfigLoader LOADER = new ManifestProjectConfigLoader();

    private final UpdateTargetCatalog catalog = new UpdateTargetCatalog();

    @Test
    void collectsMutableSurfacesWithCanonicalPathsAndAliasOwnership() {
        List<UpdateTarget> targets = catalog.collect(manifest("""
                [versions]
                shared = "1.0.0"

                [dependencies]
                "com.example:aliased" = { versionRef = "shared" }
                "com.example:direct" = "2.0.0"

                [dependencies.processor]
                "com.example:processor" = "3.0.0"

                [platforms]
                "com.example:bom" = "4.0.0"

                [dependencies.constraints]
                "com.example:constrained" = { version = "5.0.0", reason = "contract" }
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
        // A virtual workspace root owns [platforms] and [versions] and nothing project-local.
        AuthoredManifest workspace = LOADER.document("""
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["apps/api"]

                [platforms]
                "org.junit:junit-bom" = "5.10.2"
                """).authored();

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
        List<UpdateTarget> targets = catalog.collect(manifest("""
                [dependencies]
                "com.example:main" = "1.0.0"
                [dependencies.api]
                "com.example:api" = "1.0.0"
                [dependencies.runtime]
                "com.example:runtime" = "1.0.0"
                [dependencies.provided]
                "com.example:provided" = "1.0.0"
                [dependencies.dev]
                "com.example:dev" = "1.0.0"
                [dependencies.test]
                "com.example:test" = "1.0.0"
                [dependencies.processor]
                "com.example:processor" = "1.0.0"
                [dependencies.test-processor]
                "com.example:test-processor" = "1.0.0"
                """), "zolt.toml", "zolt.lock");

        assertEquals(List.of(
                "[dependencies]",
                "[dependencies.api]",
                "[dependencies.runtime]",
                "[dependencies.provided]",
                "[dependencies.dev]",
                "[dependencies.test]",
                "[dependencies.processor]",
                "[dependencies.test-processor]"), targets.stream().map(UpdateTarget::section).toList());
    }

    @Test
    void omitsSnapshotLiteralsAndKeepsIdentityStableAcrossVersions() {
        AuthoredManifest before = manifest("""
                [dependencies]
                "com.example:lib" = "1.0.0"
                "com.example:snapshot" = "2.0.0-SNAPSHOT"
                "com.example:workspace" = { workspace = true }
                """);
        AuthoredManifest after = manifest("""
                [dependencies]
                "com.example:lib" = "1.1.0"
                "com.example:snapshot" = "2.0.0-SNAPSHOT"
                "com.example:workspace" = { workspace = true }
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
        List<UpdateTarget> targets = catalog.collect(manifest("""
                [generated.tools.openapi]
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
        UpdateTarget exec = catalog.collect(manifest("""
                [generated.tools.codegen]
                kind = "jvm"
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
        List<UpdateTarget> protobuf = catalog.collect(manifest("""
                [generated.tools.protobuf]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.28.3"
                grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcVersion = "1.68.1"

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
        AuthoredManifest config = manifest("""
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
                        "[dependencies.test]",
                        "com.example:lib")));
    }

    @Test
    void collectionOrderAndIdsAreDeterministic() {
        AuthoredManifest config = manifest("""
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
        AuthoredManifest config = manifest("""
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
                manifest("""
                        [dependencies]
                        "com.example:lib" = "1.0.0"
                        """),
                "zolt.toml",
                "zolt.lock").getFirst();
        Map<UpdateTargetKey, UpdateTargetCatalog.Entry> entries = new LinkedHashMap<>();

        UpdateTargetCatalog.addUnique(entries, entry);

        assertThrows(IllegalStateException.class, () -> UpdateTargetCatalog.addUnique(entries, entry));
    }

    private static AuthoredManifest manifest(String body) {
        return LOADER.document(source(body)).authored();
    }

    private static ProjectConfig discovery(String body) {
        return LOADER.load(source(body));
    }

    private static String source(String body) {
        return """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s
                """.formatted(body);
    }
}
