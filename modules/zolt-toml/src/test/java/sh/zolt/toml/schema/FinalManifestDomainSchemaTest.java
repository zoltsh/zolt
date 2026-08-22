package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class FinalManifestDomainSchemaTest extends FinalManifestSchemaTestSupport {
    @Test
    void recordsExactGeneratedFieldShapesAndDomainOrder() {
        assertEquals(
                List.of(
                        Map.entry("kind", ManifestValueKind.STRING),
                        Map.entry("coordinate", ManifestValueKind.STRING),
                        Map.entry("version", ManifestValueKind.STRING),
                        Map.entry("versionRef", ManifestValueKind.STRING),
                        Map.entry("protocCoordinate", ManifestValueKind.STRING),
                        Map.entry("protocVersion", ManifestValueKind.STRING),
                        Map.entry("protocVersionRef", ManifestValueKind.STRING),
                        Map.entry("grpcCoordinate", ManifestValueKind.STRING),
                        Map.entry("grpcVersion", ManifestValueKind.STRING),
                        Map.entry("grpcVersionRef", ManifestValueKind.STRING),
                        Map.entry("coordinates", ManifestValueKind.INLINE_TABLE_ARRAY),
                        Map.entry("mainClass", ManifestValueKind.STRING),
                        Map.entry("binary", ManifestValueKind.STRING),
                        Map.entry("versionCommand", ManifestValueKind.STRING_ARRAY),
                        Map.entry("versionExpect", ManifestValueKind.STRING),
                        Map.entry("allowUnpinnedTool", ManifestValueKind.BOOLEAN)),
                fieldShapes("generated.tools.<id>"));
        assertEquals(
                List.of(
                        Map.entry("kind", ManifestValueKind.STRING),
                        Map.entry("generator", ManifestValueKind.STRING),
                        Map.entry("library", ManifestValueKind.STRING),
                        Map.entry("apiPackage", ManifestValueKind.STRING),
                        Map.entry("modelPackage", ManifestValueKind.STRING),
                        Map.entry("invokerPackage", ManifestValueKind.STRING),
                        Map.entry("config", ManifestValueKind.STRING),
                        Map.entry("templateDir", ManifestValueKind.STRING),
                        Map.entry("validateSpec", ManifestValueKind.BOOLEAN),
                        Map.entry("options", ManifestValueKind.INLINE_TABLE),
                        Map.entry("additionalProperties", ManifestValueKind.INLINE_TABLE),
                        Map.entry("configOptions", ManifestValueKind.INLINE_TABLE),
                        Map.entry("globalProperties", ManifestValueKind.INLINE_TABLE),
                        Map.entry("typeMappings", ManifestValueKind.INLINE_TABLE),
                        Map.entry("importMappings", ManifestValueKind.INLINE_TABLE)),
                fieldShapes("generated.presets.<id>"));

        List<Map.Entry<String, ManifestValueKind>> stepShape = List.of(
                Map.entry("kind", ManifestValueKind.STRING),
                Map.entry("language", ManifestValueKind.STRING),
                Map.entry("tool", ManifestValueKind.STRING),
                Map.entry("mainClass", ManifestValueKind.STRING),
                Map.entry("args", ManifestValueKind.STRING_ARRAY),
                Map.entry("input", ManifestValueKind.STRING),
                Map.entry("inputs", ManifestValueKind.STRING_ARRAY),
                Map.entry("output", ManifestValueKind.STRING),
                Map.entry("produces", ManifestValueKind.STRING),
                Map.entry("into", ManifestValueKind.STRING),
                Map.entry("preset", ManifestValueKind.STRING),
                Map.entry("generator", ManifestValueKind.STRING),
                Map.entry("library", ManifestValueKind.STRING),
                Map.entry("apiPackage", ManifestValueKind.STRING),
                Map.entry("modelPackage", ManifestValueKind.STRING),
                Map.entry("invokerPackage", ManifestValueKind.STRING),
                Map.entry("config", ManifestValueKind.STRING),
                Map.entry("templateDir", ManifestValueKind.STRING),
                Map.entry("validateSpec", ManifestValueKind.BOOLEAN),
                Map.entry("options", ManifestValueKind.INLINE_TABLE),
                Map.entry("additionalProperties", ManifestValueKind.INLINE_TABLE),
                Map.entry("configOptions", ManifestValueKind.INLINE_TABLE),
                Map.entry("globalProperties", ManifestValueKind.INLINE_TABLE),
                Map.entry("typeMappings", ManifestValueKind.INLINE_TABLE),
                Map.entry("importMappings", ManifestValueKind.INLINE_TABLE),
                Map.entry("javaPackage", ManifestValueKind.STRING),
                Map.entry("grpc", ManifestValueKind.BOOLEAN),
                Map.entry("cache", ManifestValueKind.STRING),
                Map.entry("cwd", ManifestValueKind.STRING),
                Map.entry("env", ManifestValueKind.INLINE_TABLE),
                Map.entry("secretEnv", ManifestValueKind.INLINE_TABLE),
                Map.entry("inheritEnv", ManifestValueKind.STRING_ARRAY),
                Map.entry("timeoutSeconds", ManifestValueKind.INTEGER),
                Map.entry("required", ManifestValueKind.BOOLEAN),
                Map.entry("clean", ManifestValueKind.BOOLEAN));
        assertEquals(stepShape, fieldShapes("generated.main.<id>"));
        assertEquals(stepShape, fieldShapes("generated.test.<id>"));

        assertEquals(IntStream.rangeClosed(6_301, 6_316).boxed().toList(), fieldOrders("generated.tools.<id>"));
        assertEquals(IntStream.rangeClosed(6_401, 6_415).boxed().toList(), fieldOrders("generated.presets.<id>"));
        assertEquals(IntStream.rangeClosed(6_501, 6_535).boxed().toList(), fieldOrders("generated.main.<id>"));
        assertEquals(IntStream.rangeClosed(6_601, 6_635).boxed().toList(), fieldOrders("generated.test.<id>"));
    }

    @Test
    void matchesConcreteGeneratedIdsToTheirNamedSchemaItems() {
        ManifestSchemaMatch<ManifestSection> tool = registry
                .matchSection(path("generated.tools.openapi"))
                .orElseThrow();
        assertEquals("generated.tools.<id>", tool.descriptor().path().toString());
        assertEquals(Map.of("id", "openapi"), tool.bindings());

        ManifestSchemaMatch<ManifestField> input = registry
                .matchField(path("generated.main.public-api.input"))
                .orElseThrow();
        assertEquals("generated.main.<id>.input", input.descriptor().path().toString());
        assertEquals(Map.of("id", "public-api"), input.bindings());
    }

    @Test
    void recordsExactPackagingFieldShapesAndDomainOrder() {
        assertEquals(
                List.of(
                        Map.entry("mode", ManifestValueKind.STRING),
                        Map.entry("sources", ManifestValueKind.BOOLEAN),
                        Map.entry("javadoc", ManifestValueKind.BOOLEAN),
                        Map.entry("testJar", ManifestValueKind.BOOLEAN),
                        Map.entry("duplicates", ManifestValueKind.STRING),
                        Map.entry("manifest.<attribute>", ManifestValueKind.STRING)),
                fieldShapes("package"));
        assertEquals(
                List.of(
                        Map.entry("members", ManifestValueKind.BOOLEAN_OR_STRING_ARRAY),
                        Map.entry("exclude", ManifestValueKind.STRING_ARRAY),
                        Map.entry("versions.<coordinate>", ManifestValueKind.STRING_OR_INLINE_TABLE),
                        Map.entry("imports.<coordinate>", ManifestValueKind.STRING_OR_INLINE_TABLE)),
                fieldShapes("bom"));
        assertEquals(
                List.of(Map.entry("native", ManifestValueKind.BOOLEAN)),
                fieldShapes("framework.spring-boot"));
        assertEquals(
                List.of(
                        Map.entry("name", ManifestValueKind.STRING),
                        Map.entry("output", ManifestValueKind.STRING),
                        Map.entry("args", ManifestValueKind.STRING_ARRAY)),
                fieldShapes("native"));
        assertEquals(
                List.of(
                        7_001,
                        7_002,
                        7_003,
                        7_004,
                        7_005,
                        7_011,
                        7_101,
                        7_102,
                        7_111,
                        7_121,
                        7_201,
                        7_301,
                        7_302,
                        7_303),
                registry.fields().stream()
                        .filter(field -> field.canonicalOrder() >= 7_000
                                && field.canonicalOrder() < 8_000)
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void recordsExactPublishingAndCommandFieldShapesAndDomainOrder() {
        assertEquals(
                List.of(
                        Map.entry("release", ManifestValueKind.STRING),
                        Map.entry("snapshot", ManifestValueKind.STRING),
                        Map.entry("repositories.<id>.url", ManifestValueKind.STRING),
                        Map.entry("repositories.<id>.credentials", ManifestValueKind.STRING),
                        Map.entry("signing.method", ManifestValueKind.STRING),
                        Map.entry("signing.keyId", ManifestValueKind.STRING),
                        Map.entry("signing.passphraseEnv", ManifestValueKind.STRING),
                        Map.entry("central.tokenEnv", ManifestValueKind.STRING),
                        Map.entry("central.mode", ManifestValueKind.STRING),
                        Map.entry("central.name", ManifestValueKind.STRING),
                        Map.entry("central.url", ManifestValueKind.STRING)),
                fieldShapes("publish"));
        assertEquals(
                List.of(
                        Map.entry("description", ManifestValueKind.STRING),
                        Map.entry("run", ManifestValueKind.STRING_ARRAY),
                        Map.entry("cwd", ManifestValueKind.STRING),
                        Map.entry("env", ManifestValueKind.INLINE_TABLE)),
                fieldShapes("tasks.<id>"));
        assertEquals(
                List.of(Map.entry("<id>", ManifestValueKind.STRING_ARRAY)),
                fieldShapes("aliases"));
        assertEquals(
                List.of(
                        8_001,
                        8_002,
                        8_101,
                        8_102,
                        8_201,
                        8_202,
                        8_203,
                        8_301,
                        8_302,
                        8_303,
                        8_304),
                registry.fields().stream()
                        .filter(field -> field.canonicalOrder() >= 8_000
                                && field.canonicalOrder() < 9_000)
                        .map(ManifestField::canonicalOrder)
                        .toList());
        assertEquals(
                List.of(9_001, 9_002, 9_003, 9_004, 9_101),
                registry.fields().stream()
                        .filter(field -> field.canonicalOrder() >= 9_000
                                && field.canonicalOrder() < 10_000)
                        .map(ManifestField::canonicalOrder)
                        .toList());
    }

    @Test
    void matchesDynamicPublishingAndCommandEntriesWithoutInventingNestedMapFields() {
        ManifestSchemaMatch<ManifestSection> repository = registry
                .matchSection(path("publish.repositories.company-releases"))
                .orElseThrow();
        assertEquals("publish.repositories.<id>", repository.descriptor().path().toString());
        assertEquals(Map.of("id", "company-releases"), repository.bindings());

        ManifestSchemaMatch<ManifestField> repositoryUrl = registry
                .matchField(path("publish.repositories.company-releases.url"))
                .orElseThrow();
        assertEquals(
                "publish.repositories.<id>.url",
                repositoryUrl.descriptor().path().toString());
        assertEquals(Map.of("id", "company-releases"), repositoryUrl.bindings());

        ManifestSchemaMatch<ManifestField> taskRun = registry
                .matchField(path("tasks.release-notes.run"))
                .orElseThrow();
        assertEquals("tasks.<id>.run", taskRun.descriptor().path().toString());
        assertEquals(Map.of("id", "release-notes"), taskRun.bindings());

        ManifestSchemaMatch<ManifestField> alias = registry
                .matchField(path("aliases.ci"))
                .orElseThrow();
        assertEquals("aliases.<id>", alias.descriptor().path().toString());
        assertEquals(Map.of("id", "ci"), alias.bindings());

        assertEquals(SectionKind.COLLECTION, registry
                .matchSection(path("publish.repositories"))
                .orElseThrow()
                .descriptor()
                .kind());
        assertTrue(registry.matchSection(path("publish.routes")).isEmpty());
        assertTrue(registry.matchField(path("publish.routes.release")).isEmpty());
        assertTrue(registry.matchField(path("publish.artifacts")).isEmpty());
        assertTrue(registry.matchField(path("publish.central.baseUrl")).isEmpty());
        assertTrue(registry.matchField(path("publish.central.publishingType")).isEmpty());
        assertTrue(registry.matchSection(path("commands.tasks.release-notes")).isEmpty());
        assertTrue(registry.matchField(path("commands.aliases.ci")).isEmpty());
        assertTrue(registry.matchField(path("tasks.release-notes.env.RELEASE_CHANNEL")).isEmpty());
    }

    @Test
    void matchesExternalPackagingKeysAndRejectsQuarkusFrameworkTable() {
        ManifestSchemaMatch<ManifestField> attribute = registry
                .matchField(path("package.manifest.Automatic-Module-Name"))
                .orElseThrow();
        assertEquals("package.manifest.<attribute>", attribute.descriptor().path().toString());
        assertEquals(Map.of("attribute", "Automatic-Module-Name"), attribute.bindings());

        ManifestSchemaMatch<ManifestField> version = registry
                .matchField(new ManifestPath(
                        List.of("bom", "versions", "org.postgresql:postgresql")))
                .orElseThrow();
        ManifestSchemaMatch<ManifestField> imported = registry
                .matchField(new ManifestPath(
                        List.of("bom", "imports", "com.fasterxml.jackson:jackson-bom")))
                .orElseThrow();
        assertEquals("bom.versions.<coordinate>", version.descriptor().path().toString());
        assertEquals(Map.of("coordinate", "org.postgresql:postgresql"), version.bindings());
        assertEquals("bom.imports.<coordinate>", imported.descriptor().path().toString());
        assertEquals(
                Map.of("coordinate", "com.fasterxml.jackson:jackson-bom"),
                imported.bindings());

        assertTrue(registry.matchSection(path("framework.quarkus")).isEmpty());
        assertTrue(registry.matchField(path("framework.quarkus.layout")).isEmpty());
    }
}
