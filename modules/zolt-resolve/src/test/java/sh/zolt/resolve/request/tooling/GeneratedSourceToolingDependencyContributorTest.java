package sh.zolt.resolve.request.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GeneratedSourceToolingDependencyContributorTest {
    private static final PackageId OPENAPI_GENERATOR =
            new PackageId("org.openapitools", "openapi-generator-cli");
    private static final PackageId PROTOC = new PackageId("com.google.protobuf", "protoc");
    private static final PackageId GRPC_PLUGIN = new PackageId("io.grpc", "protoc-gen-grpc-java");
    private static final PackageId JOOQ_CODEGEN = new PackageId("org.jooq", "jooq-codegen");
    private static final PackageId POSTGRES = new PackageId("org.postgresql", "postgresql");

    private final GeneratedSourceToolingDependencyContributor contributor =
            new GeneratedSourceToolingDependencyContributor(new CoordinateParser());

    @Test
    void addsOpenApiToolAsDirectToolRequest() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(openApiConfig("""
                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"
                """), requests);

        DependencyRequest request = onlyRequest(requests, OPENAPI_GENERATOR);
        assertEquals("7.11.0", request.requestedVersion());
        assertEquals(DependencyScope.TOOL_OPENAPI, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());
    }

    @Test
    void reportsMissingOpenApiToolCoordinateClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(openApiConfig("""
                        [generated.tools.openapi]
                        version = "7.11.0"
                        """), new ArrayList<>()));

        assertTrue(exception.getMessage().contains("OpenAPI generation requires [generated.openapiTool].coordinate"));
        assertTrue(exception.getMessage().contains("run `zolt resolve`"));
    }

    @Test
    void reportsMissingOpenApiToolVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(openApiConfig("""
                        [generated.tools.openapi]
                        coordinate = "org.openapitools:openapi-generator-cli"
                        """), new ArrayList<>()));

        assertTrue(exception.getMessage().contains("OpenAPI generation requires [generated.openapiTool].version"));
        assertTrue(exception.getMessage().contains("org.openapitools:openapi-generator-cli"));
        assertTrue(exception.getMessage().contains("run `zolt resolve`"));
    }

    @Test
    void addsProtobufToolAsDirectToolRequest() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(protobufConfig("""
                [generated.tools.protobuf]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.28.3"
                grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcVersion = "1.68.1"
                """), requests);

        DependencyRequest request = onlyRequest(requests, PROTOC);
        assertEquals("4.28.3", request.requestedVersion());
        assertEquals(DependencyScope.TOOL_PROTOBUF, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());

        DependencyRequest grpcPlugin = onlyRequest(requests, GRPC_PLUGIN);
        assertEquals("1.68.1", grpcPlugin.requestedVersion());
        assertEquals(DependencyScope.TOOL_PROTOBUF, grpcPlugin.scope());
        assertEquals(RequestOrigin.DIRECT, grpcPlugin.origin());
    }

    @Test
    void reportsMissingProtobufToolVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(protobufConfig("""
                        [generated.tools.protobuf]
                        protocCoordinate = "com.google.protobuf:protoc"
                        """), new ArrayList<>()));

        assertTrue(exception.getMessage().contains("Protobuf generation requires [generated.protobufTool].protocVersion"));
        assertTrue(exception.getMessage().contains("com.google.protobuf:protoc"));
    }

    @Test
    void reportsMissingGrpcPluginVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(protobufConfig("""
                        [generated.tools.protobuf]
                        protocCoordinate = "com.google.protobuf:protoc"
                        protocVersion = "4.28.3"
                        grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                        """), new ArrayList<>()));

        assertTrue(exception.getMessage().contains("Protobuf gRPC generation requires [generated.protobufTool].grpcPluginVersion"));
        assertTrue(exception.getMessage().contains("io.grpc:protoc-gen-grpc-java"));
        assertTrue(exception.getMessage().contains("run `zolt resolve`"));
    }

    @Test
    void doesNotDuplicateExistingToolScopeRequest() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                OPENAPI_GENERATOR,
                "7.11.0",
                DependencyScope.TOOL_OPENAPI,
                RequestOrigin.DIRECT));

        contributor.contribute(openApiConfig("""
                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"
                """), requests);

        assertEquals(1, requests.stream()
                .filter(request -> request.packageId().equals(OPENAPI_GENERATOR))
                .count());
    }

    @Test
    void addsEachExecToolCoordinateAsDirectToolRequest() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(execConfig(), requests);

        DependencyRequest jooq = onlyRequest(requests, JOOQ_CODEGEN);
        assertEquals("3.19.15", jooq.requestedVersion());
        assertEquals(DependencyScope.TOOL_EXEC, jooq.scope());
        assertEquals(RequestOrigin.DIRECT, jooq.origin());

        DependencyRequest postgres = onlyRequest(requests, POSTGRES);
        assertEquals("42.7.4", postgres.requestedVersion());
        assertEquals(DependencyScope.TOOL_EXEC, postgres.scope());
    }

    @Test
    void doesNotDuplicateExecToolCoordinatesAcrossSteps() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(execConfig(), requests);

        assertEquals(1, requests.stream().filter(request -> request.packageId().equals(JOOQ_CODEGEN)).count());
        assertEquals(1, requests.stream().filter(request -> request.packageId().equals(POSTGRES)).count());
    }

    private static ProjectConfig execConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "exec-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                jooq = "3.19.15"

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [
                    { coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" },
                    { coordinate = "org.postgresql:postgresql", version = "42.7.4" },
                ]
                mainClass = "org.jooq.codegen.GenerationTool"

                [generated.main.jooq-model]
                kind = "exec"
                tool = "jooq"
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/jooq"
                produces = "java-sources"

                [generated.main.jooq-extra]
                kind = "exec"
                tool = "jooq"
                inputs = ["src/main/jooq/extra.xml"]
                output = "target/generated/sources/jooq-extra"
                produces = "java-sources"
                """);
    }

    private static DependencyRequest onlyRequest(List<DependencyRequest> requests, PackageId packageId) {
        return requests.stream()
                .filter(request -> request.packageId().equals(packageId))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectConfig openApiConfig(String toolSection) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "openapi-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """.formatted(toolSection));
    }

    private static ProjectConfig protobufConfig(String toolSection) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "protobuf-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s

                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                """.formatted(toolSection));
    }
}
