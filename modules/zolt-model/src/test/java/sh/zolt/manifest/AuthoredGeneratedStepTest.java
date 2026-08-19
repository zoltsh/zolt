package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredGeneratedStepTest {
    @Test
    void openApiAndProtobufRetainOmittedDerivedDefaults() {
        AuthoredOpenApiStep openApi = new AuthoredOpenApiStep(
                GeneratedStepSettings.defaultsOmitted(),
                Optional.empty(),
                new ResourceGlob("src/main/openapi/public-api.yaml"),
                Optional.empty(),
                Optional.of(new LocalId("spring-client")),
                AuthoredOpenApiOptions.empty());
        AuthoredProtobufStep protobuf = new AuthoredProtobufStep(
                GeneratedStepSettings.defaultsOmitted(),
                Optional.empty(),
                List.of(new ResourceGlob("src/main/proto/service.proto")),
                Optional.empty(),
                Optional.of("com.example.protocol"),
                Optional.empty());

        assertEquals(Optional.empty(), openApi.output());
        assertEquals(Optional.empty(), protobuf.tool());
        assertEquals("spring-client", openApi.preset().orElseThrow().value());
        assertEquals(Optional.empty(), protobuf.grpc());
    }

    @Test
    void protobufAndDeclaredRootSortAndCopyNonemptyInputs() {
        ArrayList<ResourceGlob> inputs = new ArrayList<>(List.of(
                new ResourceGlob("src/z.proto"),
                new ResourceGlob("src/a.proto")));
        AuthoredProtobufStep protobuf = new AuthoredProtobufStep(
                GeneratedStepSettings.defaultsOmitted(), Optional.empty(), inputs,
                Optional.empty(), Optional.empty(), Optional.of(false));
        inputs.clear();

        assertEquals(
                List.of(new ResourceGlob("src/a.proto"), new ResourceGlob("src/z.proto")),
                protobuf.inputs());
        assertThrows(UnsupportedOperationException.class, () -> protobuf.inputs().clear());
        assertThrows(IllegalArgumentException.class, () -> new AuthoredDeclaredRootStep(
                GeneratedStepSettings.defaultsOmitted(),
                List.of(),
                new ManifestRelativePath("target/generated/test-sources/fixtures")));
    }

    @Test
    void execRetainsArgOrderAndCanonicalizesUnorderedInputsAndEnvironment() {
        ArrayList<String> args = new ArrayList<>(List.of("", "src/config.xml"));
        LinkedHashMap<EnvironmentVariableName, String> env = new LinkedHashMap<>();
        env.put(new EnvironmentVariableName("NODE_ENV"), "production");
        AuthoredExecStep exec = exec(
                new LocalId("jooq"),
                Optional.empty(),
                args,
                Optional.of(GeneratedCachePolicy.NONE),
                env,
                Map.of(new EnvironmentVariableName("DB_PASSWORD"),
                        new EnvironmentVariableName("CODEGEN_DB_PASSWORD")),
                List.of(new EnvironmentVariableName("HTTP_PROXY")),
                GeneratedOutputKind.JAVA_SOURCES,
                Optional.empty());
        args.clear();
        env.clear();

        assertEquals(List.of("", "src/config.xml"), exec.args());
        assertEquals("NODE_ENV", exec.env().keySet().iterator().next().value());
        assertEquals(600, exec.timeoutSeconds().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> exec.args().clear());
        assertThrows(UnsupportedOperationException.class, () -> exec.secretEnv().clear());
    }

    @Test
    void projectPseudoToolExclusivelyOwnsStepMainClass() {
        assertEquals(
                "com.example.Codegen",
                exec(
                                new LocalId("project"),
                                Optional.of(new JavaBinaryClassName("com.example.Codegen")),
                                List.of(), Optional.empty(), Map.of(), Map.of(), List.of(),
                                GeneratedOutputKind.JAVA_SOURCES, Optional.empty())
                        .mainClass().orElseThrow().value());
        assertThrows(IllegalArgumentException.class, () -> exec(
                new LocalId("project"), Optional.empty(), List.of(), Optional.empty(),
                Map.of(), Map.of(), List.of(), GeneratedOutputKind.JAVA_SOURCES, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> exec(
                new LocalId("jooq"),
                Optional.of(new JavaBinaryClassName("com.example.Codegen")),
                List.of(), Optional.empty(), Map.of(), Map.of(), List.of(),
                GeneratedOutputKind.JAVA_SOURCES, Optional.empty()));
    }

    @Test
    void execEnforcesCacheResourceAndEnvironmentSafety() {
        Map<EnvironmentVariableName, EnvironmentVariableName> secret = Map.of(
                new EnvironmentVariableName("DB_PASSWORD"),
                new EnvironmentVariableName("CODEGEN_DB_PASSWORD"));
        assertThrows(IllegalArgumentException.class, () -> exec(
                new LocalId("jooq"), Optional.empty(), List.of(), Optional.empty(), Map.of(),
                secret, List.of(), GeneratedOutputKind.JAVA_SOURCES, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> exec(
                new LocalId("jooq"), Optional.empty(), List.of(), Optional.of(GeneratedCachePolicy.NONE),
                Map.of(), Map.of(), List.of(), GeneratedOutputKind.JAVA_SOURCES,
                Optional.of(new ManifestRelativePath("META-INF"))));
        assertThrows(IllegalArgumentException.class, () -> exec(
                new LocalId("jooq"), Optional.empty(), List.of(), Optional.of(GeneratedCachePolicy.NONE),
                Map.of(new EnvironmentVariableName("APP_ENV"), "one"), Map.of(),
                List.of(new EnvironmentVariableName("app_env")),
                GeneratedOutputKind.JAVA_SOURCES, Optional.empty()));

        assertFalse(exec(
                        new LocalId("jooq"), Optional.empty(), List.of(),
                        Optional.of(GeneratedCachePolicy.NONE), Map.of(), Map.of(), List.of(),
                        GeneratedOutputKind.RESOURCES,
                        Optional.of(new ManifestRelativePath("META-INF")))
                .into().isEmpty());
    }

    private static AuthoredExecStep exec(
            LocalId tool,
            Optional<JavaBinaryClassName> mainClass,
            List<String> args,
            Optional<GeneratedCachePolicy> cache,
            Map<EnvironmentVariableName, String> env,
            Map<EnvironmentVariableName, EnvironmentVariableName> secretEnv,
            List<EnvironmentVariableName> inheritEnv,
            GeneratedOutputKind produces,
            Optional<ManifestRelativePath> into) {
        return new AuthoredExecStep(
                GeneratedStepSettings.defaultsOmitted(),
                tool,
                mainClass,
                args,
                List.of(new ResourceGlob("src/main/schema/*.sql")),
                new ManifestRelativePath("target/generated/sources/jooq"),
                produces,
                into,
                cache,
                Optional.empty(),
                env,
                secretEnv,
                inheritEnv,
                Optional.of(600));
    }
}
