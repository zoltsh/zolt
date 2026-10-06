package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProtobufGenerationSettings;

final class BuildServiceKspOrchestrationTest {
    @TempDir
    private Path projectDirectory;

    @Test
    void preparesKspBeforePublishedOutputDiscovery() throws IOException {
        Path source = projectDirectory.resolve("src/main/java/demo/App.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo\nclass App\n");

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> new BuildService(required -> jdk()).build(
                        ProjectBuildContext.standalone(projectDirectory),
                        config(),
                        emptyClasspaths(),
                        List.of(),
                        false));

        assertTrue(failure.getMessage().contains("zolt.lock has no direct"));
        assertTrue(failure.getMessage().contains("kotlin-compiler-embeddable"));
        assertTrue(failure.getMessage().contains("tool-kotlin"));
        assertTrue(Files.notExists(projectDirectory.resolve("target/generated/ksp/main/symbols")));
    }

    private static ProjectConfig config() {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "21",
                "UTF-8",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_RELEASE,
                "",
                "",
                "2.2.0",
                "",
                "",
                Set.of());
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults().withGeneratedSources(List.of(step()), List.of()),
                NativeSettings.defaults(),
                compiler,
                PackageSettings.defaults());
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

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty, empty);
    }

    private static JdkStatus jdk() {
        return new JdkStatus(
                Optional.of(Path.of("/jdk")),
                Optional.of(Path.of("/jdk/bin/java")),
                Optional.of(Path.of("/jdk/bin/javac")),
                Optional.of(Path.of("/jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
    }
}
