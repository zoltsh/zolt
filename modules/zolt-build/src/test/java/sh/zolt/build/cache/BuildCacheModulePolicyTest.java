package sh.zolt.build.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProtobufGenerationSettings;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BuildCacheModulePolicyTest {
    @Test
    void plainModuleIsCacheable() {
        assertTrue(BuildCacheModulePolicy.cacheable(configWith()));
    }

    @Test
    void hermeticContentExecStepIsCacheable() {
        assertTrue(BuildCacheModulePolicy.cacheable(configWith(execStep("content"))));
    }

    @Test
    void cacheNoneExecStepTaintsTheModule() {
        ProjectConfig config = configWith(execStep("none"));
        assertFalse(BuildCacheModulePolicy.cacheable(config));
        assertTrue(BuildCacheModulePolicy.taintReason(config).orElseThrow().contains("cache = \"none\""));
    }

    @Test
    void secretEnvHonestCacheNonePathExcludesModuleFromSharedCache() {
        // The full Hole 2 chain: secretEnv + cache = "content" without a salt does not parse (the parser
        // rejects it), so the honest path is cache = "none". A secretEnv step on that path is non-hermetic
        // and therefore excluded from the shared build-output cache. secretEnv values never enter the lock,
        // fingerprints, plans, or logs.
        ProjectConfig config = new sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.gen]
                kind = "jvm"
                coordinates = [{ coordinate = "com.example:gen", version = "1.0.0" }]
                mainClass = "com.example.Main"

                [generated.main.model]
                kind = "exec"
                tool = "gen"
                inputs = ["src/main/gen/in.txt"]
                output = "target/generated/sources/gen"
                produces = "java-sources"
                cache = "none"
                secretEnv = { DB_PASSWORD = "CI_DB_PASSWORD" }
                """);
        assertFalse(BuildCacheModulePolicy.cacheable(config));
        assertTrue(BuildCacheModulePolicy.taintReason(config).isPresent());
    }

    private static GeneratedSourceStep execStep(String cache) {
        ExecGenerationSettings exec = new ExecGenerationSettings(
                "gen-tool",
                new ExecToolSettings(
                        "jvm",
                        List.of(new ExecToolCoordinate("com.example:gen-tool", Optional.of("1.0.0"), Optional.empty())),
                        "com.example.Main"),
                List.of(),
                null,
                Optional.empty(),
                Map.of(),
                cache);
        return new GeneratedSourceStep(
                "gen",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated",
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }

    private static ProjectConfig configWith(GeneratedSourceStep... steps) {
        ProjectConfig base = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
        return base.withBuildSettings(base.build().withGeneratedSources(List.of(steps), List.of()));
    }
}
