package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class KotlinJvmPreviewPolicyTest {
    @Test
    void mainPreviewEnablesEveryOwnedRuntime() {
        ProjectConfig config = config(List.of("-Xjvm-enable-preview"), List.of());

        assertTrue(KotlinJvmPreviewPolicy.mainEnabled(config));
        assertTrue(KotlinJvmPreviewPolicy.testRuntimeEnabled(config));
        assertEquals(
                List.of("--enable-preview"),
                KotlinJvmPreviewPolicy.mainJvmArguments(config));
        assertEquals(
                List.of("-Dtest=true", "--enable-preview"),
                KotlinJvmPreviewPolicy.testJvmArguments(config, List.of("-Dtest=true")));
    }

    @Test
    void testPreviewAffectsOnlyTestRuntimeAndDoesNotDuplicateExplicitFlag() {
        ProjectConfig config = config(List.of(), List.of("-Xjvm-enable-preview"));

        assertFalse(KotlinJvmPreviewPolicy.mainEnabled(config));
        assertTrue(KotlinJvmPreviewPolicy.mainJvmArguments(config).isEmpty());
        assertTrue(KotlinJvmPreviewPolicy.testRuntimeEnabled(config));
        assertEquals(
                List.of("--enable-preview", "-Dtest=true"),
                KotlinJvmPreviewPolicy.testJvmArguments(
                        config,
                        List.of("--enable-preview", "-Dtest=true")));
    }

    @Test
    void ordinaryCompilationPreservesConfiguredRuntimeArguments() {
        ProjectConfig config = config(List.of(), List.of());

        assertFalse(KotlinJvmPreviewPolicy.mainEnabled(config));
        assertFalse(KotlinJvmPreviewPolicy.testRuntimeEnabled(config));
        assertEquals(
                List.of("-Dtest=true"),
                KotlinJvmPreviewPolicy.testJvmArguments(config, List.of("-Dtest=true")));
    }

    private static ProjectConfig config(
            List<String> mainArguments,
            List<String> testArguments) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                NativeSettings.defaults(),
                new CompilerSettings(null, null, "", "", mainArguments, testArguments));
    }
}
