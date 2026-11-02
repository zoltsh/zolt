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

final class GroovyToolingDependencyContributorTest {
    private static final PackageId GROOVY = new PackageId("org.apache.groovy", "groovy");

    private final GroovyToolingDependencyContributor contributor =
            new GroovyToolingDependencyContributor();

    @Test
    void contributesTheExactConfiguredCompilerAsADirectToolRequest() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(config("4.0.22"), requests);

        DependencyRequest request = requests.getFirst();
        assertEquals(1, requests.size());
        assertEquals(GROOVY, request.packageId());
        assertEquals("4.0.22", request.requestedVersion());
        assertEquals(DependencyScope.TOOL_GROOVY, request.scope());
        assertEquals(RequestOrigin.DIRECT, request.origin());
        assertEquals(RequestVersionOrigin.DECLARED, request.versionOrigin());
        assertTrue(request.artifactVariant().isDefault());
    }

    @Test
    void contributesNothingWhenTheGroovyToolchainIsAbsent() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(config(""), requests);

        assertTrue(requests.isEmpty());
    }

    @Test
    void keepsTheCompilerToolLaneDistinctFromAnApplicationDependency() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                GROOVY,
                "4.0.19",
                DependencyScope.COMPILE,
                RequestOrigin.DIRECT));

        contributor.contribute(config("4.0.22"), requests);
        contributor.contribute(config("4.0.22"), requests);

        assertEquals(2, requests.size());
        DependencyRequest tool = requests.stream()
                .filter(request -> request.scope() == DependencyScope.TOOL_GROOVY)
                .findFirst()
                .orElseThrow();
        assertEquals("4.0.22", tool.requestedVersion());
        assertEquals(RequestOrigin.DIRECT, tool.origin());
    }

    private static ProjectConfig config(String groovyVersion) {
        String toolchain = groovyVersion.isBlank()
                ? ""
                : """

                  [toolchain.groovy]
                  version = "%s"
                  """.formatted(groovyVersion);
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
