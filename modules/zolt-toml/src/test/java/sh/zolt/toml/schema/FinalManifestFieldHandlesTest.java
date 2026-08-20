package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FinalManifestFieldHandlesTest {
    private final ManifestSchemaRegistry registry = FinalManifestSchema.registry();

    @Test
    void identityHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestIdentityFields.fields(),
                List.of(
                        field("workspace.name", 1_010),
                        field("workspace.members.default", 1_110),
                        field("workspace.members.include", 1_120),
                        field("workspace.members.exclude", 1_130),
                        field("workspace.project.group", 1_210),
                        field("workspace.project.version", 1_220),
                        field("workspace.project.java", 1_230),
                        field("workspace.project.license", 1_240),
                        field("project.name", 2_010),
                        field("project.version", 2_020),
                        field("project.group", 2_030),
                        field("project.java", 2_040),
                        field("project.main", 2_050),
                        field("project.description", 2_060),
                        field("project.url", 2_070),
                        field("project.issues", 2_080),
                        field("project.license", 2_090),
                        field("project.scm.url", 2_110),
                        field("project.scm.connection", 2_120),
                        field("project.scm.developerConnection", 2_130),
                        field("project.scm.tag", 2_140),
                        field("project.developers.<id>.name", 2_210),
                        field("project.developers.<id>.email", 2_220),
                        field("project.developers.<id>.organization", 2_230),
                        field("project.developers.<id>.url", 2_240)));
    }

    @Test
    void toolchainHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestToolchainFields.fields(),
                List.of(
                        field("toolchain.zolt.version", 3_010),
                        field("toolchain.java.version", 3_110),
                        field("toolchain.java.distribution", 3_120),
                        field("toolchain.java.features", 3_130),
                        field("toolchain.java.policy", 3_140),
                        field("toolchain.java.test.version", 3_210),
                        field("toolchain.java.test.distribution", 3_220),
                        field("toolchain.java.test.policy", 3_230)));
    }

    @Test
    void sharedHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestSharedFields.fields(),
                List.of(
                        field("versions.<id>", 4_010),
                        field("repositories.central", 4_110),
                        field("repositories.order", 4_120),
                        field("repositories.<id>.url", 4_210),
                        field("repositories.<id>.credentials", 4_220),
                        field("credentials.<id>.tokenEnv", 4_310),
                        field("credentials.<id>.usernameEnv", 4_320),
                        field("credentials.<id>.passwordEnv", 4_330),
                        field("platforms.<coordinate>", 4_410)));
    }

    @Test
    void dependencyHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestDependencyFields.fields(),
                List.of(
                        field("dependencies.<coordinate>", 5_001),
                        field("dependencies.api.<coordinate>", 5_011),
                        field("dependencies.runtime.<coordinate>", 5_021),
                        field("dependencies.provided.<coordinate>", 5_031),
                        field("dependencies.dev.<coordinate>", 5_041),
                        field("dependencies.test.<coordinate>", 5_051),
                        field("dependencies.processor.<coordinate>", 5_061),
                        field("dependencies.test-processor.<coordinate>", 5_071),
                        field("dependencies.constraints.<coordinate>", 5_081),
                        field("dependencies.policy.conflicts", 5_091),
                        field("dependencies.policy.deny", 5_092),
                        field("dependencies.policy.licenses.allow", 5_101),
                        field("dependencies.policy.licenses.deny", 5_102),
                        field("dependencies.policy.licenses.unknown", 5_103),
                        field("dependencies.license-exceptions.<coordinate>.allow", 5_111),
                        field("dependencies.license-exceptions.<coordinate>.version", 5_112),
                        field("dependencies.license-exceptions.<coordinate>.reason", 5_113)));
    }

    @Test
    void buildHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestBuildFields.fields(),
                List.of(
                        field("build.sources", 6_001),
                        field("build.output.root", 6_011),
                        field("build.output.main", 6_012),
                        field("build.output.test", 6_013),
                        field("build.output.integration", 6_014),
                        field("build.metadata.buildInfo", 6_021),
                        field("build.metadata.git", 6_022),
                        field("build.metadata.reproducible", 6_023)));
    }

    @Test
    void compilerHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestCompilerFields.fields(),
                List.of(
                        field("compiler.encoding", 6_101),
                        field("compiler.jdkApi", 6_102),
                        field("compiler.args", 6_103),
                        field("compiler.test.jdkApi", 6_111),
                        field("compiler.test.args", 6_112),
                        field("compiler.generated.main", 6_121),
                        field("compiler.generated.test", 6_122)));
    }

    @Test
    void resourceHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestResourceFields.fields(),
                List.of(
                        field("resources.main", 6_201),
                        field("resources.test", 6_202),
                        field("resources.filter.targets", 6_211),
                        field("resources.filter.include", 6_212),
                        field("resources.filter.missing", 6_213),
                        field("resources.tokens.<id>", 6_221)));
    }

    @Test
    void generatedToolHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestGeneratedToolFields.fields(),
                List.of(
                        field("generated.tools.<id>.kind", 6_301),
                        field("generated.tools.<id>.coordinate", 6_302),
                        field("generated.tools.<id>.version", 6_303),
                        field("generated.tools.<id>.versionRef", 6_304),
                        field("generated.tools.<id>.protocCoordinate", 6_305),
                        field("generated.tools.<id>.protocVersion", 6_306),
                        field("generated.tools.<id>.protocVersionRef", 6_307),
                        field("generated.tools.<id>.grpcCoordinate", 6_308),
                        field("generated.tools.<id>.grpcVersion", 6_309),
                        field("generated.tools.<id>.grpcVersionRef", 6_310),
                        field("generated.tools.<id>.coordinates", 6_311),
                        field("generated.tools.<id>.mainClass", 6_312),
                        field("generated.tools.<id>.binary", 6_313),
                        field("generated.tools.<id>.versionCommand", 6_314),
                        field("generated.tools.<id>.versionExpect", 6_315),
                        field("generated.tools.<id>.allowUnpinnedTool", 6_316)));
        assertEquals(
                List.of(FinalManifestGeneratedToolFields.GENERATED_TOOL_COORDINATES),
                FinalManifestGeneratedToolFields.fields().stream()
                        .filter(field -> field.objectShape().isPresent())
                        .toList());
        assertSame(
                FinalManifestObjectShapes.GENERATED_ARTIFACT_REQUEST,
                FinalManifestGeneratedToolFields.GENERATED_TOOL_COORDINATES
                        .objectShape()
                        .orElseThrow());
    }

    @Test
    void generatedPresetHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestGeneratedPresetFields.fields(),
                List.of(
                        field("generated.presets.<id>.kind", 6_401),
                        field("generated.presets.<id>.generator", 6_402),
                        field("generated.presets.<id>.library", 6_403),
                        field("generated.presets.<id>.apiPackage", 6_404),
                        field("generated.presets.<id>.modelPackage", 6_405),
                        field("generated.presets.<id>.invokerPackage", 6_406),
                        field("generated.presets.<id>.config", 6_407),
                        field("generated.presets.<id>.templateDir", 6_408),
                        field("generated.presets.<id>.validateSpec", 6_409),
                        field("generated.presets.<id>.options", 6_410),
                        field("generated.presets.<id>.additionalProperties", 6_411),
                        field("generated.presets.<id>.configOptions", 6_412),
                        field("generated.presets.<id>.globalProperties", 6_413),
                        field("generated.presets.<id>.typeMappings", 6_414),
                        field("generated.presets.<id>.importMappings", 6_415)));
    }

    @Test
    void generatedMainHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestGeneratedMainFields.fields(),
                List.of(
                        field("generated.main.<id>.kind", 6_501),
                        field("generated.main.<id>.language", 6_502),
                        field("generated.main.<id>.tool", 6_503),
                        field("generated.main.<id>.mainClass", 6_504),
                        field("generated.main.<id>.args", 6_505),
                        field("generated.main.<id>.input", 6_506),
                        field("generated.main.<id>.inputs", 6_507),
                        field("generated.main.<id>.output", 6_508),
                        field("generated.main.<id>.produces", 6_509),
                        field("generated.main.<id>.into", 6_510),
                        field("generated.main.<id>.preset", 6_511),
                        field("generated.main.<id>.generator", 6_512),
                        field("generated.main.<id>.library", 6_513),
                        field("generated.main.<id>.apiPackage", 6_514),
                        field("generated.main.<id>.modelPackage", 6_515),
                        field("generated.main.<id>.invokerPackage", 6_516),
                        field("generated.main.<id>.config", 6_517),
                        field("generated.main.<id>.templateDir", 6_518),
                        field("generated.main.<id>.validateSpec", 6_519),
                        field("generated.main.<id>.options", 6_520),
                        field("generated.main.<id>.additionalProperties", 6_521),
                        field("generated.main.<id>.configOptions", 6_522),
                        field("generated.main.<id>.globalProperties", 6_523),
                        field("generated.main.<id>.typeMappings", 6_524),
                        field("generated.main.<id>.importMappings", 6_525),
                        field("generated.main.<id>.javaPackage", 6_526),
                        field("generated.main.<id>.grpc", 6_527),
                        field("generated.main.<id>.cache", 6_528),
                        field("generated.main.<id>.cwd", 6_529),
                        field("generated.main.<id>.env", 6_530),
                        field("generated.main.<id>.secretEnv", 6_531),
                        field("generated.main.<id>.inheritEnv", 6_532),
                        field("generated.main.<id>.timeoutSeconds", 6_533),
                        field("generated.main.<id>.required", 6_534),
                        field("generated.main.<id>.clean", 6_535)));
    }

    @Test
    void generatedTestHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestGeneratedTestFields.fields(),
                List.of(
                        field("generated.test.<id>.kind", 6_601),
                        field("generated.test.<id>.language", 6_602),
                        field("generated.test.<id>.tool", 6_603),
                        field("generated.test.<id>.mainClass", 6_604),
                        field("generated.test.<id>.args", 6_605),
                        field("generated.test.<id>.input", 6_606),
                        field("generated.test.<id>.inputs", 6_607),
                        field("generated.test.<id>.output", 6_608),
                        field("generated.test.<id>.produces", 6_609),
                        field("generated.test.<id>.into", 6_610),
                        field("generated.test.<id>.preset", 6_611),
                        field("generated.test.<id>.generator", 6_612),
                        field("generated.test.<id>.library", 6_613),
                        field("generated.test.<id>.apiPackage", 6_614),
                        field("generated.test.<id>.modelPackage", 6_615),
                        field("generated.test.<id>.invokerPackage", 6_616),
                        field("generated.test.<id>.config", 6_617),
                        field("generated.test.<id>.templateDir", 6_618),
                        field("generated.test.<id>.validateSpec", 6_619),
                        field("generated.test.<id>.options", 6_620),
                        field("generated.test.<id>.additionalProperties", 6_621),
                        field("generated.test.<id>.configOptions", 6_622),
                        field("generated.test.<id>.globalProperties", 6_623),
                        field("generated.test.<id>.typeMappings", 6_624),
                        field("generated.test.<id>.importMappings", 6_625),
                        field("generated.test.<id>.javaPackage", 6_626),
                        field("generated.test.<id>.grpc", 6_627),
                        field("generated.test.<id>.cache", 6_628),
                        field("generated.test.<id>.cwd", 6_629),
                        field("generated.test.<id>.env", 6_630),
                        field("generated.test.<id>.secretEnv", 6_631),
                        field("generated.test.<id>.inheritEnv", 6_632),
                        field("generated.test.<id>.timeoutSeconds", 6_633),
                        field("generated.test.<id>.required", 6_634),
                        field("generated.test.<id>.clean", 6_635)));
    }

    @Test
    void testHandlesAreTheExactRegisteredDescriptors() {
        assertCatalog(
                FinalManifestTestFields.fields(),
                List.of(
                        field("test.sources.java", 6_701),
                        field("test.sources.groovy", 6_702),
                        field("test.runtime.jvmArgs", 6_711),
                        field("test.runtime.properties", 6_712),
                        field("test.runtime.env", 6_713),
                        field("test.runtime.events", 6_714),
                        field("test.integration.sources", 6_721),
                        field("test.integration.resources", 6_722),
                        field("test.suites.<id>.classes", 6_731),
                        field("test.suites.<id>.excludeClasses", 6_732),
                        field("test.suites.<id>.tags", 6_733),
                        field("test.suites.<id>.excludeTags", 6_734),
                        field("test.suites.<id>.workers", 6_735),
                        field("test.suites.<id>.locks", 6_736)));
    }

    @Test
    void handleCatalogsCoverTheCompleteRegisteredPrefixWithoutDuplicates() {
        List<ManifestField> handles = handles();
        List<ManifestField> registered = registry.fields().stream()
                .filter(field -> field.canonicalOrder() < 6_900)
                .toList();

        assertEquals(195, handles.size());
        assertEquals(195, handles.stream().map(ManifestField::path).distinct().count());
        assertEquals(195, handles.stream().map(ManifestField::canonicalOrder).distinct().count());
        assertEquals(handles, registered);
        for (int index = 0; index < handles.size(); index++) {
            assertSame(handles.get(index), registered.get(index));
        }
    }

    private void assertCatalog(
            List<ManifestField> handles,
            List<Map.Entry<String, Integer>> expected) {
        assertEquals(
                expected,
                handles.stream()
                        .map(field -> field(field.path().toString(), field.canonicalOrder()))
                        .toList());
        handles.forEach(field -> assertSame(field, registry.field(field.path()).orElseThrow()));
    }

    private static List<ManifestField> handles() {
        ArrayList<ManifestField> handles = new ArrayList<>();
        handles.addAll(FinalManifestIdentityFields.fields());
        handles.addAll(FinalManifestToolchainFields.fields());
        handles.addAll(FinalManifestSharedFields.fields());
        handles.addAll(FinalManifestDependencyFields.fields());
        handles.addAll(FinalManifestBuildFields.fields());
        handles.addAll(FinalManifestCompilerFields.fields());
        handles.addAll(FinalManifestResourceFields.fields());
        handles.addAll(FinalManifestGeneratedToolFields.fields());
        handles.addAll(FinalManifestGeneratedPresetFields.fields());
        handles.addAll(FinalManifestGeneratedMainFields.fields());
        handles.addAll(FinalManifestGeneratedTestFields.fields());
        handles.addAll(FinalManifestTestFields.fields());
        return List.copyOf(handles);
    }

    private static Map.Entry<String, Integer> field(String path, int canonicalOrder) {
        return Map.entry(path, canonicalOrder);
    }
}
