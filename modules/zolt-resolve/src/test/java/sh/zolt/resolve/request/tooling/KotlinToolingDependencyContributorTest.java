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

    private static ProjectConfig config(String kotlinVersion) {
        String toolchain = kotlinVersion.isBlank()
                ? ""
                : """

                  [toolchain.kotlin]
                  version = "%s"
                  """.formatted(kotlinVersion);
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                %s
                """.formatted(toolchain));
    }
}
