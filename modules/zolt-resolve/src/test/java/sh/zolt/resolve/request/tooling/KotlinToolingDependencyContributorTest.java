package sh.zolt.resolve.request.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.request.RequestVersionOrigin;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class KotlinToolingDependencyContributorTest {
    private static final PackageId KOTLIN_COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");
    private static final PackageId KOTLIN_KAPT =
            new PackageId("org.jetbrains.kotlin", "kotlin-annotation-processing-embeddable");
    private static final PackageId KOTLIN_SERIALIZATION = new PackageId(
            "org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable");
    private static final PackageId KOTLIN_ALL_OPEN = new PackageId(
            "org.jetbrains.kotlin", "kotlin-allopen-compiler-plugin-embeddable");

    private final KotlinToolingDependencyContributor contributor =
            new KotlinToolingDependencyContributor();

    @Test
    void contributesTheExactConfiguredCompilerAsADirectToolRequest() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(config("2.2.0"), requests);

        DependencyRequest request = requests.getFirst();
        assertEquals(1, requests.size());
        assertEquals(KOTLIN_COMPILER, request.packageId());
        assertEquals("2.2.0", request.requestedVersion());
        assertEquals(DependencyScope.TOOL_KOTLIN, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());
        assertEquals(RequestVersionOrigin.DECLARED, request.versionOrigin());
        assertTrue(request.artifactVariant().isDefault());
    }

    @Test
    void contributesNothingWhenTheKotlinToolchainIsAbsent() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(config(""), requests);

        assertTrue(requests.isEmpty());
    }

    @Test
    void keepsTheCompilerToolLaneDistinctFromAnApplicationDependency() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                KOTLIN_COMPILER,
                "1.9.24",
                DependencyScope.COMPILE,
                RequestOrigin.DIRECT));

        contributor.contribute(config("2.2.0"), requests);
        contributor.contribute(config("2.2.0"), requests);

        assertEquals(2, requests.size());
        DependencyRequest tool = requests.stream()
                .filter(request -> request.scope() == DependencyScope.TOOL_KOTLIN)
                .findFirst()
                .orElseThrow();
        assertEquals("2.2.0", tool.requestedVersion());
        assertEquals(RequestOrigin.DIRECT, tool.origin());
    }

    @Test
    void contributesKaptOnlyForConfiguredProcessorLanes() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(config("2.2.0", true), requests);
        contributor.contribute(config("2.2.0", true), requests);

        assertEquals(2, requests.size());
        DependencyRequest kapt = requests.stream()
                .filter(request -> request.packageId().equals(KOTLIN_KAPT))
                .findFirst()
                .orElseThrow();
        assertEquals("2.2.0", kapt.requestedVersion());
        assertEquals(DependencyScope.TOOL_KOTLIN, kapt.scope());
        assertEquals(RequestOrigin.DIRECT, kapt.origin());
        assertEquals(RequestVersionOrigin.DECLARED, kapt.versionOrigin());
    }

    @Test
    void contributesTheVersionAlignedCompilerPluginsOnce() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(config("2.2.0", false, true, true), requests);
        contributor.contribute(config("2.2.0", false, true, true), requests);

        assertEquals(3, requests.size());
        for (PackageId packageId : List.of(KOTLIN_SERIALIZATION, KOTLIN_ALL_OPEN)) {
            DependencyRequest plugin = requests.stream()
                    .filter(request -> request.packageId().equals(packageId))
                    .findFirst()
                    .orElseThrow();
            assertEquals("2.2.0", plugin.requestedVersion());
            assertEquals(DependencyScope.TOOL_KOTLIN, plugin.scope());
            assertEquals(RequestOrigin.DIRECT, plugin.origin());
            assertEquals(RequestVersionOrigin.DECLARED, plugin.versionOrigin());
        }
    }

    private static ProjectConfig config(String kotlinVersion) {
        return config(kotlinVersion, false);
    }

    private static ProjectConfig config(
            String kotlinVersion,
            boolean processor) {
        return config(kotlinVersion, processor, false);
    }

    private static ProjectConfig config(
            String kotlinVersion,
            boolean processor,
            boolean serialization) {
        return config(kotlinVersion, processor, serialization, false);
    }

    private static ProjectConfig config(
            String kotlinVersion,
            boolean processor,
            boolean serialization,
            boolean spring) {
        String plugins = serialization && spring
                ? "\nplugins = [\"serialization\", \"spring\"]"
                : serialization ? "\nplugins = [\"serialization\"]" : "";
        String toolchain = kotlinVersion.isBlank()
                ? ""
                : """

                  [toolchain.kotlin]
                  version = "%s"%s
                  """.formatted(kotlinVersion, plugins);
        String processors = processor
                ? """

                  [dependencies.processor]
                  "com.example:processor" = "1.0.0"
                  """
                : "";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                %s
                %s
                """.formatted(toolchain, processors));
    }
}
