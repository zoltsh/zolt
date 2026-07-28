package sh.zolt.resolve.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltTomlParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ToolingDependencyContributorTest {
    private static final PackageId JUNIT_CONSOLE = new PackageId("org.junit.platform", "junit-platform-console");
    private static final PackageId SPRING_BOOT_LOADER = new PackageId("org.springframework.boot", "spring-boot-loader");
    private static final PackageId SPRING_BOOT_AOT_TOOL = new PackageId("org.springframework.boot", "spring-boot");
    private static final PackageId OPENAPI_GENERATOR = new PackageId("org.openapitools", "openapi-generator-cli");
    private static final PackageId PROTOC = new PackageId("com.google.protobuf", "protoc");
    private static final PackageId GRPC_PLUGIN = new PackageId("io.grpc", "protoc-gen-grpc-java");
    private static final PackageId JACOCO_AGENT = new PackageId("org.jacoco", "org.jacoco.agent");
    private static final PackageId JACOCO_CLI = new PackageId("org.jacoco", "org.jacoco.cli");

    private final ToolingDependencyContributor contributor = new ToolingDependencyContributor(new CoordinateParser());

    @Test
    void addsJUnitConsoleToolingForTestInputs() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(
                testDependencyConfig(),
                Map.of(JUNIT_CONSOLE, "1.12.0"),
                requests,
                false);

        DependencyRequest request = onlyRequest(requests, JUNIT_CONSOLE);
        assertEquals("1.12.0", request.requestedVersion());
        assertEquals(DependencyScope.TEST, request.scope());
        assertEquals(RequestOrigin.TRANSITIVE, request.origin());
        assertEquals(RequestVersionOrigin.INJECTED, request.versionOrigin());
    }

    @Test
    void fallsBackToDefaultJUnitConsoleVersionWhenManagedVersionIsBlank() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(
                testDependencyConfig(),
                Map.of(JUNIT_CONSOLE, " "),
                requests,
                false);

        DependencyRequest request = onlyRequest(requests, JUNIT_CONSOLE);
        assertEquals("1.11.4", request.requestedVersion());
        assertEquals(DependencyScope.TEST, request.scope());
    }

    @Test
    void alignsInjectedConsoleWithDeclaredJupiterFiveLine() {
        PackageId jupiter = new PackageId("org.junit.jupiter", "junit-jupiter");
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                jupiter,
                "5.14.4",
                DependencyScope.TEST,
                RequestOrigin.DIRECT));

        contributor.contribute(testDependencyConfig(), Map.of(), requests, false);

        assertEquals("1.14.4", onlyRequest(requests, JUNIT_CONSOLE).requestedVersion());
    }

    @Test
    void declaredJupiterLineOverridesManagedConsoleVersion() {
        PackageId jupiter = new PackageId("org.junit.jupiter", "junit-jupiter");
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                jupiter,
                "5.11.4",
                DependencyScope.TEST,
                RequestOrigin.DIRECT));

        contributor.contribute(
                testDependencyConfig(),
                Map.of(JUNIT_CONSOLE, "1.14.4"),
                requests,
                false);

        assertEquals("1.11.4", onlyRequest(requests, JUNIT_CONSOLE).requestedVersion());
    }

    @Test
    void managedConsoleVersionOverridesManagedJupiterLine() {
        PackageId jupiter = new PackageId("org.junit.jupiter", "junit-jupiter");
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                jupiter,
                "5.10.2",
                DependencyScope.TEST,
                RequestOrigin.DIRECT,
                RequestVersionOrigin.MANAGED));

        contributor.contribute(
                testDependencyConfig(),
                Map.of(JUNIT_CONSOLE, "1.11.4"),
                requests,
                false);

        assertEquals("1.11.4", onlyRequest(requests, JUNIT_CONSOLE).requestedVersion());
    }

    @Test
    void derivesConsoleVersionFromManagedJupiterWhenConsoleIsNotManaged() {
        PackageId jupiter = new PackageId("org.junit.jupiter", "junit-jupiter");
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                jupiter,
                "5.10.2",
                DependencyScope.TEST,
                RequestOrigin.DIRECT,
                RequestVersionOrigin.MANAGED));

        contributor.contribute(testDependencyConfig(), Map.of(), requests, false);

        assertEquals("1.10.2", onlyRequest(requests, JUNIT_CONSOLE).requestedVersion());
    }

    @Test
    void alignsInjectedConsoleWithDeclaredJupiterSixLine() {
        PackageId jupiter = new PackageId("org.junit.jupiter", "junit-jupiter");
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                jupiter,
                "6.1.2",
                DependencyScope.TEST,
                RequestOrigin.DIRECT));

        contributor.contribute(testDependencyConfig(), Map.of(), requests, false);

        assertEquals("6.1.2", onlyRequest(requests, JUNIT_CONSOLE).requestedVersion());
    }

    @Test
    void usesNewestDeclaredJUnitPlatformLine() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                new PackageId("org.junit.jupiter", "junit-jupiter"),
                "5.11.4",
                DependencyScope.TEST,
                RequestOrigin.DIRECT));
        requests.add(new DependencyRequest(
                new PackageId("org.junit.platform", "junit-platform-engine"),
                "1.14.4",
                DependencyScope.TEST,
                RequestOrigin.DIRECT));

        contributor.contribute(testDependencyConfig(), Map.of(), requests, false);

        assertEquals("1.14.4", onlyRequest(requests, JUNIT_CONSOLE).requestedVersion());
    }

    @Test
    void doesNotAddJUnitConsoleWhenConsoleAlreadyEntersTestClasspath() {
        PackageId standalone = new PackageId("org.junit.platform", "junit-platform-console-standalone");
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                standalone,
                "1.12.0",
                DependencyScope.TEST,
                RequestOrigin.DIRECT));

        contributor.contribute(testDependencyConfig(), Map.of(), requests, false);

        assertEquals(1, requests.size());
        assertEquals(standalone, requests.getFirst().packageId());
    }

    @Test
    void addsSpringBootLoaderFromManagedPlatformVersion() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(
                baseConfig().withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT)),
                Map.of(SPRING_BOOT_LOADER, "4.0.6"),
                requests,
                false);

        DependencyRequest request = onlyRequest(requests, SPRING_BOOT_LOADER);
        assertEquals("4.0.6", request.requestedVersion());
        assertEquals(DependencyScope.RUNTIME, request.scope());
        assertEquals(RequestOrigin.TRANSITIVE, request.origin());
    }

    @Test
    void springBootLoaderReportsMissingManagedVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(
                        baseConfig().withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT)),
                        Map.of(),
                        new ArrayList<>(),
                        false));

        assertTrue(exception.getMessage().contains("Spring Boot package mode requires package tool artifact"));
        assertTrue(exception.getMessage().contains("Add the Spring Boot platform to [platforms]"));
    }

    @Test
    void addsSpringBootAotToolFromManagedPlatformVersion() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(
                springBootNativeConfig(),
                Map.of(SPRING_BOOT_AOT_TOOL, "4.0.6"),
                requests,
                false);

        DependencyRequest request = onlyRequest(requests, SPRING_BOOT_AOT_TOOL);
        assertEquals("4.0.6", request.requestedVersion());
        assertEquals(DependencyScope.TOOL_SPRING_AOT, request.scope());
        assertEquals(RequestOrigin.TRANSITIVE, request.origin());
    }

    @Test
    void springBootAotToolReportsMissingManagedVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(
                        springBootNativeConfig(),
                        Map.of(),
                        new ArrayList<>(),
                        false));

        assertTrue(exception.getMessage().contains("Spring Boot native AOT requires tool artifact"));
        assertTrue(exception.getMessage().contains("Add the Spring Boot platform to [platforms]"));
    }

    @Test
    void addsOpenApiToolAsDirectToolRequest() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(openApiConfig(), Map.of(), requests, false);

        DependencyRequest request = onlyRequest(requests, OPENAPI_GENERATOR);
        assertEquals("7.11.0", request.requestedVersion());
        assertEquals(DependencyScope.TOOL_OPENAPI, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());
    }

    @Test
    void addsProtobufToolsAsDirectToolRequests() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(protobufConfig(), Map.of(), requests, false);

        DependencyRequest protoc = onlyRequest(requests, PROTOC);
        assertEquals("4.28.3", protoc.requestedVersion());
        assertEquals(DependencyScope.TOOL_PROTOBUF, protoc.scope());
        assertEquals(RequestOrigin.DIRECT, protoc.origin());

        DependencyRequest grpcPlugin = onlyRequest(requests, GRPC_PLUGIN);
        assertEquals("1.68.1", grpcPlugin.requestedVersion());
        assertEquals(DependencyScope.TOOL_PROTOBUF, grpcPlugin.scope());
        assertEquals(RequestOrigin.DIRECT, grpcPlugin.origin());
    }

    @Test
    void addsCoverageToolsOnlyWhenRequestedAndTestsExist() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(testDependencyConfig(), Map.of(), requests, true);

        DependencyRequest agent = onlyRequest(requests, JACOCO_AGENT);
        assertEquals("0.8.14", agent.requestedVersion());
        assertEquals(DependencyScope.TOOL_COVERAGE, agent.scope());
        assertTrue(agent.artifactDescriptor().isPresent());
        assertEquals(Optional.of("runtime"), agent.artifactDescriptor().orElseThrow().classifier());

        DependencyRequest cli = onlyRequest(requests, JACOCO_CLI);
        assertEquals("0.8.14", cli.requestedVersion());
        assertEquals(DependencyScope.TOOL_COVERAGE, cli.scope());
    }

    @Test
    void doesNotAddCoverageToolsWithoutTestInputs() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(baseConfig(), Map.of(), requests, true);

        assertTrue(requests.isEmpty());
    }

    @Test
    void doesNotDuplicateExistingCoverageToolRequests() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                JACOCO_AGENT,
                "0.8.14",
                DependencyScope.TOOL_COVERAGE,
                RequestOrigin.TRANSITIVE));
        requests.add(new DependencyRequest(
                JACOCO_CLI,
                "0.8.14",
                DependencyScope.TOOL_COVERAGE,
                RequestOrigin.TRANSITIVE));

        contributor.contribute(testDependencyConfig(), Map.of(), requests, true);

        assertEquals(1, countRequests(requests, JACOCO_AGENT));
        assertEquals(1, countRequests(requests, JACOCO_CLI));
    }

    private static DependencyRequest onlyRequest(List<DependencyRequest> requests, PackageId packageId) {
        return requests.stream()
                .filter(request -> request.packageId().equals(packageId))
                .findFirst()
                .orElseThrow();
    }

    private static long countRequests(List<DependencyRequest> requests, PackageId packageId) {
        return requests.stream()
                .filter(request -> request.packageId().equals(packageId))
                .count();
    }

    private static ProjectConfig baseConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);
    }

    private static ProjectConfig testDependencyConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [test.dependencies]
                "com.example:app" = "1.0.0"
                """);
    }

    private static ProjectConfig springBootNativeConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "spring-native-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [framework.springBoot.native]
                enabled = true
                """);
    }

    private static ProjectConfig openApiConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "openapi-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.openapiTool]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """);
    }

    private static ProjectConfig protobufConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "protobuf-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.protobufTool]
                protocVersion = "4.28.3"
                grpcPluginVersion = "1.68.1"

                [generated.main.greeter]
                kind = "protobuf"
                language = "java"
                inputs = ["src/main/proto/greeter.proto"]
                output = "target/generated/sources/protobuf"
                """);
    }
}
