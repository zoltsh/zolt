package sh.zolt.build.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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

    @Test
    void recordsKotlinModuleMetadataInEveryAggregateDigest() throws IOException {
        Path source = projectDir.resolve("src/main/kotlin/com/example/Main.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.example\nclass Main\n");
        Path output = projectDir.resolve("target/classes");
        Path module = output.resolve("META-INF/demo.kotlin_module");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "before");
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(), List.of(), List.of(source), List.of(), List.of(), List.of());
        IncrementalCompileStateRecorder recorder = new IncrementalCompileStateRecorder();

        record(recorder, sources, output);
        IncrementalCompileState before = state(output);
        Files.writeString(module, "after");
        record(recorder, sources, output);
        IncrementalCompileState after = state(output);
        Path renamed = module.resolveSibling("renamed.kotlin_module");
        Files.move(module, renamed);
        record(recorder, sources, output);
        IncrementalCompileState renamedState = state(output);

        assertAllDigestsChanged(before, after);
        assertAllDigestsChanged(after, renamedState);
    }

    private void record(
            IncrementalCompileStateRecorder recorder,
            SourceDiscoveryResult sources,
            Path output) {
        recorder.recordMain(
                projectDir,
                config(),
                sources,
                classpaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"),
                "compiler-identity",
                GeneratedOutputAttribution.absent(),
                sources.allMainSources());
    }

    private static IncrementalCompileState state(Path output) {
        return new IncrementalCompileStateCodec()
                .read(IncrementalCompileState.mainStatePath(output))
                .orElseThrow();
    }

    private static void assertAllDigestsChanged(
            IncrementalCompileState before,
            IncrementalCompileState after) {
        assertNotEquals(before.publicAbiDigest(), after.publicAbiDigest());
        assertNotEquals(before.packagePrivateAbiDigest(), after.packagePrivateAbiDigest());
        assertNotEquals(before.outputManifestDigest(), after.outputManifestDigest());
        assertNotEquals(
                IncrementalCompileSummary.from(before).compileAbiDigest(),
                IncrementalCompileSummary.from(after).compileAbiDigest());
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
