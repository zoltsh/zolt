package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProtobufGenerationSettings;

final class GeneratedSourceToolingGateKspTest {
    @TempDir
    private Path projectDirectory;

    private final ZoltLockfileReader reader = new ZoltLockfileReader();

    @Test
    void requiresBothIsolatedKspToolGroups() throws IOException {
        Path lockfile = lockfile(List.of("ksp:ksp:engine", "unrelated"));

        assertTrue(GeneratedSourceToolingGate.kspToolingMissing(
                reader,
                lockfile,
                config(true),
                false));

        lockfile(List.of("ksp:ksp:engine", "ksp:ksp:processors"));
        assertFalse(GeneratedSourceToolingGate.kspToolingMissing(
                reader,
                lockfile,
                config(true),
                false));
    }

    @Test
    void reportsMissingGroupsActionablyOffline() throws IOException {
        Path lockfile = lockfile(List.of("unrelated"));

        BuildException failure = assertThrows(
                BuildException.class,
                () -> GeneratedSourceToolingGate.kspToolingMissing(
                        reader,
                        lockfile,
                        config(true),
                        true));

        assertTrue(failure.getMessage().contains("ksp:ksp:engine"));
        assertTrue(failure.getMessage().contains("ksp:ksp:processors"));
        assertTrue(failure.actionableError().remediation().contains("without --offline"));
    }

    @Test
    void projectsWithoutKspDoNotRequireToolExecGroups() throws IOException {
        Path lockfile = lockfile(List.of());

        assertFalse(GeneratedSourceToolingGate.kspToolingMissing(
                reader,
                lockfile,
                config(false),
                true));
    }

    private Path lockfile(List<String> groups) throws IOException {
        String toolGroups = groups.stream()
                .map(group -> "\"" + group + "\"")
                .collect(java.util.stream.Collectors.joining(", "));
        String content = """
                version = 7

                [[package]]
                id = "com.example:tool"
                version = "1.0.0"
                source = "central"
                scope = "tool-exec"
                direct = false
                toolGroups = [%s]
                dependencies = []
                """.formatted(toolGroups);
        Path path = projectDirectory.resolve("zolt.lock");
        Files.writeString(path, content);
        return path;
    }

    private static ProjectConfig config(boolean ksp) {
        ProjectConfig base = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
        return ksp
                ? base.withBuildSettings(base.build().withGeneratedSources(List.of(step()), List.of()))
                : base;
    }

    private static GeneratedSourceStep step() {
        return new GeneratedSourceStep(
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
                new KspGenerationSettings(
                        "ksp",
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:processor", "1.0.0", Optional.empty())),
                        Map.of()));
    }
}
