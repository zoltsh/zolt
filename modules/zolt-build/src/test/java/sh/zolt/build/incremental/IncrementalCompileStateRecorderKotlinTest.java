package sh.zolt.build.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class IncrementalCompileStateRecorderKotlinTest {
    @TempDir
    private Path projectDir;

    @Test
    void marksKotlinMainStateFullOnlyWithoutParsingKotlinAsJava() throws IOException {
        Path source = projectDir.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {(byte) 0xff});
        Path output = projectDir.resolve("target/classes");
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(), List.of(), List.of(source), List.of(), List.of(), List.of());

        new IncrementalCompileStateRecorder().recordMain(
                projectDir,
                config(),
                sources,
                classpaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"),
                "compiler-identity",
                GeneratedOutputAttribution.absent(),
                sources.allMainSources());

        IncrementalCompileState state = new IncrementalCompileStateCodec()
                .read(IncrementalCompileState.mainStatePath(output))
                .orElseThrow();
        assertEquals(List.of("kotlin-main-sources"), state.fallbackReasons());
        assertEquals(List.of(), state.sources());
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static ClasspathSet classpaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty, empty);
    }
}
