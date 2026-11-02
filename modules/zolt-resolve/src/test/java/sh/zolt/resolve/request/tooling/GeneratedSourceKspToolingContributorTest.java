package sh.zolt.resolve.request.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProtobufGenerationSettings;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class GeneratedSourceKspToolingContributorTest {
    private final GeneratedSourceToolingDependencyContributor contributor =
            new GeneratedSourceToolingDependencyContributor(new CoordinateParser());

    @Test
    void contributesKspRequestsToPolicyAndIsolatedResolutionPaths() {
        ProjectConfig config = config();
        List<DependencyRequest> policyRequests = new ArrayList<>();

        contributor.contribute(config, policyRequests);
        Map<String, List<DependencyRequest>> groups = contributor.execToolRequestGroups(config);

        assertEquals(List.of(
                        new PackageId("com.google.devtools.ksp", "symbol-processing-aa"),
                        new PackageId("com.example", "symbol-processor")),
                policyRequests.stream().map(DependencyRequest::packageId).toList());
        assertEquals(List.of("ksp:ksp:engine", "ksp:ksp:processors"),
                groups.keySet().stream().toList());
        assertEquals("2.2.0-2.0.2",
                groups.get("ksp:ksp:engine").getFirst().requestedVersion());
        assertEquals("1.4.0",
                groups.get("ksp:ksp:processors").getFirst().requestedVersion());
        assertEquals(DependencyScope.TOOL_EXEC,
                groups.get("ksp:ksp:processors").getFirst().scope());
    }

    private static ProjectConfig config() {
        ProjectConfig base = new ManifestProjectConfigLoader().load("""
                [project]
                name = "ksp-tooling"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        KspGenerationSettings ksp = new KspGenerationSettings(
                "ksp",
                Optional.of("2.2.0-2.0.2"),
                Optional.of("ksp-version"),
                List.of(new KspProcessorSettings(
                        "com.example:symbol-processor",
                        "1.4.0",
                        Optional.of("processor-version"))),
                Map.of("mode", "test"));
        GeneratedSourceStep step = new GeneratedSourceStep(
                "symbols",
                GeneratedSourceKind.KSP,
                "kotlin",
                "target/generated/ksp/main/symbols",
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                ksp);
        return base.withBuildSettings(base.build().withGeneratedSources(List.of(step), List.of()));
    }
}
