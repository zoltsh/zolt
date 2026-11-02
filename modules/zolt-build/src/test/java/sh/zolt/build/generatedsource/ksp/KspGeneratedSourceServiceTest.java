package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.process.SupervisedProcessResult;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProtobufGenerationSettings;

final class KspGeneratedSourceServiceTest {
    private static final String KOTLIN_VERSION = "2.2.0";
    private static final String KSP_VERSION = "2.2.0-2.0.2";

    @Test
    void runsIsolatedToolchainIntoStagingThenPublishes(@TempDir Path temporary)
            throws IOException {
        Path root = temporary.toAbsolutePath().normalize();
        Path live = root.resolve("target/generated/ksp/main/symbols");
        Files.createDirectories(live.resolve("java"));
        Files.writeString(live.resolve("java/Stale.java"), "stale");
        AtomicReference<List<String>> captured = new AtomicReference<>();
        KspJvmProcess process = new KspJvmProcess(Duration.ofSeconds(30), spec -> {
            captured.set(spec.command());
            Path generated = Path.of(option(spec.command(), "kotlin-output-dir"))
                    .resolve("demo/Generated.kt");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, "package demo\nclass Generated");
            return result(0, "");
        });

        service(root, process).generate(
                root,
                "main",
                step(),
                List.of(),
                KOTLIN_VERSION,
                jdk(root),
                KotlinCompilerOptions.defaults("21", "demo_main", false),
                List.of(root.resolve("src/main/kotlin")),
                List.of(root.resolve("src/main/java")),
                List.of(root.resolve("lib/api.jar")));

        assertFalse(Files.exists(live.resolve("java/Stale.java")));
        assertEquals(
                "package demo\nclass Generated",
                Files.readString(live.resolve("kotlin/demo/Generated.kt")));
        assertEquals(
                root.resolve("tools/ksp.jar").toString(),
                value(captured.get(), "-cp"));
        assertEquals(
                root.resolve("tools/processor.jar").toString(),
                captured.get().getLast());
        assertEquals("false", option(captured.get(), "incremental"));
        assertEquals("demo.mode=strict", option(captured.get(), "processor-options"));
        assertTrue(option(captured.get(), "output-base-dir").contains(".zolt-ksp-staging-"));
    }

    @Test
    void failedProcessorPreservesPreviousLiveOutput(@TempDir Path temporary)
            throws IOException {
        Path root = temporary.toAbsolutePath().normalize();
        Path previous = root.resolve("target/generated/ksp/main/symbols/kotlin/Previous.kt");
        Files.createDirectories(previous.getParent());
        Files.writeString(previous, "previous");
        KspJvmProcess process = new KspJvmProcess(Duration.ofSeconds(30), spec -> {
            Path partial = Path.of(option(spec.command(), "kotlin-output-dir"))
                    .resolve("Partial.kt");
            Files.writeString(partial, "partial");
            return result(2, "processor failed");
        });

        BuildException failure = assertThrows(
                BuildException.class,
                () -> service(root, process).generate(
                        root,
                        "main",
                        step(),
                        List.of(),
                        KOTLIN_VERSION,
                        jdk(root),
                        KotlinCompilerOptions.defaults("21", "demo_main", false),
                        List.of(root.resolve("src/main/kotlin")),
                        List.of(),
                        List.of()));

        assertTrue(failure.getMessage().contains("failed with exit code 2"));
        assertEquals("previous", Files.readString(previous));
        assertEquals(List.of(), stagingSiblings(previous.getParent().getParent()));
    }

    @Test
    void toolchainFailureOccursBeforeStaging(@TempDir Path temporary) throws IOException {
        Path root = temporary.toAbsolutePath().normalize();
        BuildException expected = new BuildException("toolchain unavailable");
        KspGeneratedSourceService service = new KspGeneratedSourceService(
                (packages, engine, processors, kotlin, ksp) -> {
                    throw expected;
                },
                new KspJvmCommandBuilder(":"),
                successfulProcess());

        BuildException failure = assertThrows(
                BuildException.class,
                () -> service.generate(
                        root,
                        "main",
                        step(),
                        List.of(),
                        KOTLIN_VERSION,
                        jdk(root),
                        KotlinCompilerOptions.defaults("21", "demo_main", false),
                        List.of(root.resolve("src/main/kotlin")),
                        List.of(),
                        List.of()));

        assertEquals(expected, failure);
        Path parent = root.resolve("target/generated/ksp/main");
        assertEquals(List.of(), Files.isDirectory(parent) ? stagingSiblings(parent) : List.of());
    }

    private static KspGeneratedSourceService service(Path root, KspJvmProcess process) {
        KspJvmToolchain toolchain = new KspJvmToolchain(
                KSP_VERSION,
                KOTLIN_VERSION,
                List.of(root.resolve("tools/ksp.jar")),
                List.of(root.resolve("tools/processor.jar")),
                "ksp:test");
        return new KspGeneratedSourceService(
                (packages, engine, processors, kotlin, ksp) -> {
                    assertEquals("ksp:ksp:engine", engine);
                    assertEquals("ksp:ksp:processors", processors);
                    assertEquals(KOTLIN_VERSION, kotlin);
                    assertEquals(KSP_VERSION, ksp);
                    return toolchain;
                },
                new KspJvmCommandBuilder(":"),
                process);
    }

    private static KspJvmProcess successfulProcess() {
        return new KspJvmProcess(
                Duration.ofSeconds(30),
                ignored -> result(0, ""));
    }

    private static SupervisedProcessResult result(int exitCode, String diagnostics) {
        return new SupervisedProcessResult(
                exitCode,
                diagnostics,
                false,
                false,
                false,
                -1);
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
                        Optional.of(KSP_VERSION),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:processor", "1.0.0", Optional.empty())),
                        Map.of("demo.mode", "strict")));
    }

    private static JdkStatus jdk(Path root) {
        return new JdkStatus(
                Optional.of(root.resolve("jdk")),
                Optional.of(root.resolve("jdk/bin/java")),
                Optional.of(root.resolve("jdk/bin/javac")),
                Optional.of(root.resolve("jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
    }

    private static String value(List<String> command, String argument) {
        return command.get(command.indexOf(argument) + 1);
    }

    private static String option(List<String> command, String name) {
        String prefix = "-" + name + "=";
        return command.stream()
                .filter(argument -> argument.startsWith(prefix))
                .findFirst()
                .orElseThrow()
                .substring(prefix.length());
    }

    private static List<Path> stagingSiblings(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) {
            return List.of();
        }
        try (var entries = Files.list(parent)) {
            return entries.filter(path -> path.getFileName().toString().contains(".zolt-ksp-staging-"))
                    .toList();
        }
    }
}
