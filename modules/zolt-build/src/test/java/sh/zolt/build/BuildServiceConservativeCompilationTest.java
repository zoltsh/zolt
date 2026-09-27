package sh.zolt.build;

import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.config;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.source;
import static sh.zolt.build.BuildServiceIncrementalMainCompileTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceConservativeCompilationTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void changedSourceRecompilesTheWholeMainScopeByDefault() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path changed = source(projectDir, "src/main/java/p/Changed.java", """
                package p;

                public final class Changed {
                    public String message() {
                        return "before";
                    }
                }
                """);
        source(projectDir, "src/main/java/p/Unchanged.java", "package p; public final class Unchanged {}\n");
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(changed, """
                package p;

                public final class Changed {
                    public String message() {
                        return "after";
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.mainCompilationMode());
        assertEquals("source-changed", result.mainIncrementalFallbackReason());
        assertEquals(2, result.sourceCount());
        assertEquals(1, result.mainCompileDiagnostics().sourcesChanged());
        assertEquals(2, result.mainCompileDiagnostics().sourcesRecompiled());
    }

    @Test
    void selectiveEraFingerprintForcesOneCleanFullCompileOnUpgrade() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        source(projectDir, "src/main/java/p/Main.java", "package p; public final class Main {}\n");
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Path fingerprint = projectDir.resolve("target/classes/.zolt-build-main.fingerprint");
        String oldFingerprint = Files.readString(fingerprint).replaceFirst("version=3", "version=2");
        Files.writeString(fingerprint, oldFingerprint);
        Path fingerprintState = fingerprint.resolveSibling(fingerprint.getFileName() + ".state");
        String oldState = Files.readString(fingerprintState).replaceFirst(
                "fingerprintSha256=[0-9a-f]+",
                "fingerprintSha256=" + sha256(oldFingerprint));
        Files.writeString(fingerprintState, oldState);
        Path staleOutput = projectDir.resolve("target/classes/stale/sentinel.txt");
        Files.createDirectories(staleOutput.getParent());
        Files.writeString(staleOutput, "selective-era output\n");

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.mainCompilationMode());
        assertEquals("fingerprint-mismatch:version", result.mainIncrementalFallbackReason());
        assertFalse(Files.exists(staleOutput));
        assertTrue(Files.readString(fingerprint).startsWith("version=3\n"));
    }

    @Test
    void typeAddedInsideExistingSourceFailsLikeCleanCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path holder = source(projectDir, "src/main/java/p/Holder.java", """
                package p;

                public class Holder {}
                """);
        source(projectDir, "src/main/java/p/Consumer.java", """
                package p;

                import java.util.*;

                public class Consumer {
                    List<String> names;
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(holder, """
                package p;

                public class Holder {}

                class List {}
                """);

        JavacException incremental = assertThrows(
                JavacException.class,
                () -> buildService.build(projectDir, config(), projectDir.resolve("cache")));
        wipeTarget();
        JavacException clean = assertThrows(
                JavacException.class,
                () -> buildService.build(projectDir, config(), projectDir.resolve("cache")));

        assertTrue(incremental.getMessage().contains("type List does not take parameters"), incremental.getMessage());
        assertTrue(clean.getMessage().contains("type List does not take parameters"), clean.getMessage());
    }

    @Test
    void sourceRetentionAnnotationChangeFailsLikeCleanCompilation() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path marker = source(projectDir, "src/main/java/p/Marker.java", """
                package p;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.SOURCE)
                public @interface Marker {}
                """);
        source(projectDir, "src/main/java/p/Consumer.java", """
                package p;

                @Marker
                public class Consumer {}
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(marker, """
                package p;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.SOURCE)
                public @interface Marker {
                    String required();
                }
                """);

        JavacException incremental = assertThrows(
                JavacException.class,
                () -> buildService.build(projectDir, config(), projectDir.resolve("cache")));
        wipeTarget();
        JavacException clean = assertThrows(
                JavacException.class,
                () -> buildService.build(projectDir, config(), projectDir.resolve("cache")));

        assertTrue(incremental.getMessage().contains("missing a default value"), incremental.getMessage());
        assertTrue(clean.getMessage().contains("missing a default value"), clean.getMessage());
    }

    @Test
    void outputContainingSourceRootIsRejectedWithoutDeletingSources() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        Path main = source(projectDir, "src/main/java/p/Main.java", "package p; public final class Main {}\n");
        ProjectConfig unsafe = config().withBuildSettings(new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "src/main/java",
                "target/test-classes"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> buildService.build(projectDir, unsafe, projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("Unsafe compile output layout"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
        assertEquals("package p; public final class Main {}\n", Files.readString(main));
    }

    private void wipeTarget() throws IOException {
        Path target = projectDir.resolve("target");
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
