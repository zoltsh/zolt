package sh.zolt.build;

import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.config;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.source;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceIncrementalMainOutputDriftTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void untrackedOutputClassForcesFullMainCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path first = source(projectDir, "src/main/java/com/example/First.java", firstSource("one"));
        source(projectDir, "src/main/java/com/example/Second.java", secondSource());
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Path foreign = projectDir.resolve("target/classes/com/example/Foreign.class");
        Files.write(foreign, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        Files.writeString(first, firstSource("two"));

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.mainCompilationMode());
        assertEquals("untracked-class:target/classes/com/example/Foreign.class", result.mainIncrementalFallbackReason());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/First.class")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Second.class")));
    }

    @Test
    void deletedOutputClassOfUnchangedSourceForcesFullMainCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path first = source(projectDir, "src/main/java/com/example/First.java", firstSource("one"));
        source(projectDir, "src/main/java/com/example/Second.java", secondSource());
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.delete(projectDir.resolve("target/classes/com/example/Second.class"));
        Files.writeString(first, firstSource("two"));

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.mainCompilationMode());
        assertEquals(
                "missing-expected-class:target/classes/com/example/Second.class",
                result.mainIncrementalFallbackReason());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Second.class")));
    }

    @Test
    void matchingOutputClassesKeepIncrementalMainCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path first = source(projectDir, "src/main/java/com/example/First.java", firstSource("one"));
        source(projectDir, "src/main/java/com/example/Second.java", secondSource());
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(first, firstSource("two"));

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/Foreign.class")));
    }

    private static String firstSource(String label) {
        return """
                package com.example;

                public final class First {
                    public String message() {
                        return "first %s";
                    }
                }
                """
                .formatted(label);
    }

    private static String secondSource() {
        return """
                package com.example;

                public final class Second {
                    public String message() {
                        return "second";
                    }
                }
                """;
    }
}
