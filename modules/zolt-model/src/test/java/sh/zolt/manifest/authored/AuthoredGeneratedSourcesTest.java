package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedOutputKind;
import sh.zolt.manifest.GeneratedStepSettings;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;

final class AuthoredGeneratedSourcesTest {
    @Test
    void acceptsImplicitBuiltInsAndSortsBothStepScopes() {
        LinkedHashMap<LocalId, AuthoredGeneratedStep> main = new LinkedHashMap<>();
        main.put(new LocalId("z-protocol"), protobuf(Optional.empty()));
        main.put(new LocalId("a-api"), openApi(Optional.empty(), Optional.empty()));
        LinkedHashMap<LocalId, AuthoredGeneratedStep> test = new LinkedHashMap<>();
        test.put(new LocalId("fixtures"), declaredRoot());

        AuthoredGeneratedSources generated = new AuthoredGeneratedSources(
                AuthoredGeneratedTools.empty(),
                AuthoredGeneratedPresets.empty(),
                main,
                test);
        main.clear();
        test.clear();

        assertEquals(
                List.of("a-api", "z-protocol"),
                generated.main().keySet().stream().map(LocalId::value).toList());
        assertThrows(UnsupportedOperationException.class, () -> generated.main().clear());
    }

    @Test
    void resolvesCustomToolsByExactTypedKind() {
        AuthoredGeneratedTools tools = new AuthoredGeneratedTools(Map.of(
                new LocalId("legacy-openapi"),
                new AuthoredGeneratedTool.OpenApi(Optional.empty(), Optional.empty()),
                new LocalId("jooq"),
                new AuthoredGeneratedTool.Jvm(
                        List.of(new GeneratedArtifactRequest(
                                new DependencyCoordinate("org.jooq:jooq-codegen"),
                                new DependencySelector.FixedVersion("3.20.1"))),
                        new JavaBinaryClassName("org.jooq.codegen.GenerationTool"))));

        AuthoredGeneratedSources generated = new AuthoredGeneratedSources(
                tools,
                AuthoredGeneratedPresets.empty(),
                Map.of(
                        new LocalId("api"),
                        openApi(Optional.of(new LocalId("legacy-openapi")), Optional.empty()),
                        new LocalId("model"),
                        exec(new LocalId("jooq"), Optional.empty())),
                Map.of());

        assertEquals(2, generated.main().size());
    }

    @Test
    void projectPseudoToolNeedsNoDeclarationButStillRequiresMainClass() {
        AuthoredGeneratedSources generated = new AuthoredGeneratedSources(
                AuthoredGeneratedTools.empty(),
                AuthoredGeneratedPresets.empty(),
                Map.of(new LocalId("model"), exec(
                        new LocalId("project"),
                        Optional.of(new JavaBinaryClassName("com.example.Codegen")))),
                Map.of());

        assertEquals("project",
                ((AuthoredExecStep) generated.main().get(new LocalId("model"))).tool().value());
    }

    @Test
    void rejectsMissingOrWrongKindToolReferences() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredGeneratedSources(
                AuthoredGeneratedTools.empty(),
                AuthoredGeneratedPresets.empty(),
                Map.of(new LocalId("api"), openApi(Optional.of(new LocalId("missing")), Optional.empty())),
                Map.of()));

        AuthoredGeneratedTools tools = new AuthoredGeneratedTools(Map.of(
                new LocalId("jooq"),
                new AuthoredGeneratedTool.OpenApi(Optional.empty(), Optional.empty())));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredGeneratedSources(
                tools,
                AuthoredGeneratedPresets.empty(),
                Map.of(new LocalId("model"), exec(new LocalId("jooq"), Optional.empty())),
                Map.of()));
    }

    @Test
    void requiresEveryOpenApiPresetReferenceToExist() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredGeneratedSources(
                AuthoredGeneratedTools.empty(),
                AuthoredGeneratedPresets.empty(),
                Map.of(new LocalId("api"), openApi(
                        Optional.empty(), Optional.of(new LocalId("spring-client")))),
                Map.of()));

        AuthoredGeneratedSources generated = new AuthoredGeneratedSources(
                AuthoredGeneratedTools.empty(),
                new AuthoredGeneratedPresets(Map.of(
                        new LocalId("spring-client"), AuthoredOpenApiOptions.empty())),
                Map.of(new LocalId("api"), openApi(
                        Optional.empty(), Optional.of(new LocalId("spring-client")))),
                Map.of());
        assertEquals("spring-client",
                ((AuthoredOpenApiStep) generated.main().get(new LocalId("api")))
                        .preset().orElseThrow().value());
    }

    private static AuthoredOpenApiStep openApi(
            Optional<LocalId> tool, Optional<LocalId> preset) {
        return new AuthoredOpenApiStep(
                GeneratedStepSettings.defaultsOmitted(),
                tool,
                new ResourceGlob("src/main/openapi/api.yaml"),
                Optional.empty(),
                preset,
                AuthoredOpenApiOptions.empty());
    }

    private static AuthoredProtobufStep protobuf(Optional<LocalId> tool) {
        return new AuthoredProtobufStep(
                GeneratedStepSettings.defaultsOmitted(),
                tool,
                List.of(new ResourceGlob("src/main/proto/service.proto")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static AuthoredDeclaredRootStep declaredRoot() {
        return new AuthoredDeclaredRootStep(
                GeneratedStepSettings.defaultsOmitted(),
                List.of(new ResourceGlob("src/test/fixtures")),
                new ManifestRelativePath("target/generated/test-sources/fixtures"));
    }

    private static AuthoredExecStep exec(
            LocalId tool, Optional<JavaBinaryClassName> mainClass) {
        return new AuthoredExecStep(
                GeneratedStepSettings.defaultsOmitted(), tool, mainClass, List.of(),
                List.of(new ResourceGlob("src/main/schema.sql")),
                new ManifestRelativePath("target/generated/sources/model"),
                GeneratedOutputKind.JAVA_SOURCES,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of(), Map.of(), List.of(), Optional.empty());
    }
}
