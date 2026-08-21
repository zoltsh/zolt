package sh.zolt.build;

import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.config;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.source;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceIncrementalMainBehaviorTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void bodyOnlySourceChangeUsesIncrementalMainCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path source = source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return Helper.message() + " one";
                    }
                }
                """);
        source(projectDir, "src/main/java/com/example/Helper.java", """
                package com.example;

                public final class Helper {
                    public static String message() {
                        return "helper";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static String message() {
                        return Helper.message() + " two";
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(result.mainCompilationSkipped());
        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(1, result.sourceCount());
    }

    @Test
    void abiChangingSourceWithoutDependentsStaysIncrementalMainCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path source = source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "one";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static int message() {
                        return 2;
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(1, result.sourceCount());
    }

    @Test
    void deletedSourceFallsBackToFullMainCompilationAndDeletesOwnedClass() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                }
                """);
        Path deletedSource = source(projectDir, "src/main/java/com/example/Deleted.java", """
                package com.example;

                public final class Deleted {
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.delete(deletedSource);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.mainCompilationMode());
        assertEquals("source-deleted", result.mainIncrementalFallbackReason());
        assertFalse(Files.exists(projectDir.resolve("target/classes/com/example/Deleted.class")));
    }
}
