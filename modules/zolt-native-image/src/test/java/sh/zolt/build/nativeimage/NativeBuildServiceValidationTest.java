package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.NativeImageException;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectPathException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class NativeBuildServiceValidationTest extends NativeBuildServiceTestSupport {
    @Test
    void missingMainClassFailsBeforePackaging() {
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        config(Optional.empty()),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("[project].main"));
        assertFalse(java.nio.file.Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void missingConfiguredNativeImageExecutableFailsBeforePackaging() {
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        projectDir,
                        config(Optional.of("com.example.Main")),
                        projectDir.resolve("cache"),
                        projectDir.resolve("missing/native-image")));

        assertTrue(exception.getMessage().contains("Configured Native Image executable is not available"));
        assertTrue(exception.getMessage().contains("Install GraalVM Native Image"));
        assertTrue(exception.getMessage().contains("--native-image"));
        assertFalse(java.nio.file.Files.exists(projectDir.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void rejectsNativeOutputThatEscapesProject() throws IOException {
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> service.buildNative(
                        projectDir,
                        config(
                                Optional.of("com.example.Main"),
                                new NativeSettings("demo-native", "../native-out", List.of())),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("[native].output"));
        assertTrue(exception.getMessage().contains("../native-out"));
    }

    @Test
    void rejectsNativeImageNameThatUsesPathSeparator() throws IOException {
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        NativeBuildService service = service(command -> {
            throw new AssertionError("native-image should not run");
        });

        ProjectPathException exception = assertThrows(
                ProjectPathException.class,
                () -> service.buildNative(
                        projectDir,
                        config(
                                Optional.of("com.example.Main"),
                                new NativeSettings("bin/demo", "target/native-custom", List.of())),
                        projectDir.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("[native].name"));
        assertTrue(exception.getMessage().contains("bin/demo"));
    }
}
