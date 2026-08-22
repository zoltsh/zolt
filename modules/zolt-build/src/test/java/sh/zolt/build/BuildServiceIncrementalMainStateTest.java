package sh.zolt.build;

import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.config;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.source;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceIncrementalMainStateTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void repeatedMainBuildSkipsCompilationWhenInputsAreCurrent() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);

        BuildResult first = buildService.build(projectDir, config(), projectDir.resolve("cache"));
        BuildResult second = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(first.mainCompilationSkipped());
        assertEquals("full", first.mainCompilationMode());
        assertEquals("missing-state", first.mainIncrementalFallbackReason());
        assertTrue(second.mainCompilationSkipped());
        assertEquals(1, second.sourceCount());
        assertTrue(second.mainFingerprintCheckNanos() > 0);
        assertTrue(first.mainFingerprintWriteNanos() > 0);
        assertEquals(0L, second.mainFingerprintWriteNanos());
        assertTrue(Files.exists(projectDir.resolve("target/classes/.zolt-build-main.fingerprint.state")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
    }

    @Test
    void repeatedMainBuildSkipsWhenPackageInfoDoesNotEmitAClass() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {}
                """);
        source(projectDir, "src/main/java/com/example/package-info.java", """
                /** Package documentation only. */
                package com.example;
                """);

        BuildResult first = buildService.build(projectDir, config(), projectDir.resolve("cache"));
        BuildResult second = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(first.mainCompilationSkipped());
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/package-info.class")));
        assertTrue(second.mainCompilationSkipped());
    }

    @Test
    void failedMainCompileDeletesIncrementalOwnershipState() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path mainSource = source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        assertTrue(Files.exists(projectDir.resolve("target/classes/.zolt-incremental-main.state")));
        Files.writeString(mainSource, """
                package com.example;

                public final class Main {
                    broken
                }
                """);

        assertThrows(
                JavacException.class,
                () -> buildService.build(projectDir, config(), projectDir.resolve("cache")));

        assertFalse(Files.exists(projectDir.resolve("target/classes/.zolt-incremental-main.state")));
        Files.writeString(mainSource, """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "fixed";
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.mainCompilationMode());
        assertEquals("missing-state", result.mainIncrementalFallbackReason());
    }

    @Test
    void staleMainFingerprintStateFallsBackToFullFingerprint() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(projectDir.resolve("target/classes/.zolt-build-main.fingerprint.state"), "version=stale\n");

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(result.mainCompilationSkipped());
    }
}
