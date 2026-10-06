package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompilerArgumentTestSupport;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;

final class KspJvmInvocationFactoryTest {
    private static final String KOTLIN_VERSION = "2.2.0";
    private static final String KSP_VERSION = "2.2.0-2.0.2";

    @Test
    void mapsEffectiveCompilerToolchainAndOwnedOutputPolicy(@TempDir Path temporary) {
        Path root = temporary.toAbsolutePath().normalize();
        KotlinCompilerOptions options = KotlinCompilerArgumentTestSupport.options(
                        KotlinCompilationScope.MAIN,
                        List.of(
                                "-language-version", "2.1",
                                "-jvm-default=no-compatibility",
                                "-Werror"),
                        List.of())
                .withFriendPath(root.resolve("target/classes"));
        KspOutputLayout output = output(root.resolve("staging"));

        KspJvmInvocation invocation = KspJvmInvocationFactory.create(
                root,
                output,
                jdk(root, "21.0.11"),
                toolchain(root),
                options,
                List.of(root.resolve("src/main/kotlin")),
                List.of(root.resolve("src/main/java")),
                List.of(root.resolve("lib/api.jar"), root.resolve("lib/runtime.jar")),
                settings());

        assertEquals(root.resolve("jdk/bin/java"), invocation.javaExecutable());
        assertEquals(root.resolve("jdk"), invocation.jdkHome());
        assertEquals(toolchain(root).engineClasspath(), invocation.engineClasspath());
        assertEquals(toolchain(root).processorClasspath(), invocation.processorClasspath());
        assertEquals(List.of(root.resolve("src/main/kotlin")), invocation.kotlinSourceRoots());
        assertEquals(List.of(root.resolve("src/main/java")), invocation.javaSourceRoots());
        assertEquals(
                List.of(root.resolve("lib/api.jar"), root.resolve("lib/runtime.jar")),
                invocation.libraries());
        assertEquals(List.of(root.resolve("target/classes")), invocation.friends());
        assertEquals(root, invocation.projectBaseDirectory());
        assertEquals(output.baseDirectory(), invocation.outputBaseDirectory());
        assertEquals(output.cachesDirectory(), invocation.cachesDirectory());
        assertEquals(output.classOutputDirectory(), invocation.classOutputDirectory());
        assertEquals(output.kotlinOutputDirectory(), invocation.kotlinOutputDirectory());
        assertEquals(output.javaOutputDirectory(), invocation.javaOutputDirectory());
        assertEquals(output.resourceOutputDirectory(), invocation.resourceOutputDirectory());
        assertEquals("21", invocation.jvmTarget());
        assertEquals(options.moduleName(), invocation.moduleName());
        assertEquals("2.1", invocation.languageVersion());
        assertEquals("2.1", invocation.apiVersion());
        assertEquals("no-compatibility", invocation.jvmDefaultMode());
        assertTrue(invocation.warningsAsErrors());
        assertFalse(invocation.mapAnnotationArgumentsInJava());
        assertEquals(Map.of("demo.mode", "strict"), invocation.processorOptions());
    }

    @Test
    void derivesCompilerDefaultVersionsAndJvmEightSpelling(@TempDir Path temporary) {
        Path root = temporary.toAbsolutePath().normalize();
        KotlinCompilerOptions options = KotlinCompilerOptions.defaults(
                "8", "demo_main", false, false);

        KspJvmInvocation invocation = create(root, jdk(root, "1.8.0_472"), options);

        assertEquals("1.8", invocation.jvmTarget());
        assertEquals("2.2", invocation.languageVersion());
        assertEquals("2.2", invocation.apiVersion());
    }

    @Test
    void rejectsNewerJdkWhenKspCannotMirrorJdkRelease(@TempDir Path temporary) {
        Path root = temporary.toAbsolutePath().normalize();
        KotlinCompilerOptions releaseMode = KotlinCompilerArgumentTestSupport.options(
                KotlinCompilationScope.MAIN,
                List.of(),
                List.of(),
                "17",
                "21");

        BuildException failure = assertThrows(
                BuildException.class,
                () -> create(root, jdk(root, "21.0.11"), releaseMode));
        assertTrue(failure.getMessage().contains("requires `-Xjdk-release`"), failure::getMessage);
        assertTrue(failure.actionableError().remediation().contains("Java 17"));

        KotlinCompilerOptions hostMode = KotlinCompilerOptions.defaults(
                "17", "demo_main", true);
        assertEquals("17", create(root, jdk(root, "21.0.11"), hostMode).jvmTarget());
    }

    @Test
    void rejectsUnqualifiedPreviewAndMismatchedToolchain(@TempDir Path temporary) {
        Path root = temporary.toAbsolutePath().normalize();
        KotlinCompilerOptions preview = KotlinCompilerArgumentTestSupport.options(
                KotlinCompilationScope.MAIN,
                List.of("-Xjvm-enable-preview"),
                List.of());

        BuildException previewFailure = assertThrows(
                BuildException.class,
                () -> create(root, jdk(root, "21.0.11"), preview));
        assertTrue(previewFailure.getMessage().contains("JVM preview"));

        KspGenerationSettings mismatched = new KspGenerationSettings(
                "ksp",
                Optional.of("2.2.0-2.0.3"),
                Optional.empty(),
                settings().processors(),
                Map.of());
        BuildException mismatch = assertThrows(
                BuildException.class,
                () -> KspJvmInvocationFactory.create(
                        root,
                        output(root.resolve("staging")),
                        jdk(root, "21.0.11"),
                        toolchain(root),
                        KotlinCompilerOptions.defaults("21", "demo_main", false),
                        List.of(root.resolve("src/main/kotlin")),
                        List.of(),
                        List.of(),
                        mismatched));
        assertTrue(mismatch.getMessage().contains("does not match resolved toolchain"));
    }

    private static KspJvmInvocation create(
            Path root,
            JdkStatus jdk,
            KotlinCompilerOptions options) {
        return KspJvmInvocationFactory.create(
                root,
                output(root.resolve("staging")),
                jdk,
                toolchain(root),
                options,
                List.of(root.resolve("src/main/kotlin")),
                List.of(),
                List.of(),
                settings());
    }

    private static KspJvmToolchain toolchain(Path root) {
        return new KspJvmToolchain(
                KSP_VERSION,
                KOTLIN_VERSION,
                List.of(root.resolve("tools/ksp.jar")),
                List.of(root.resolve("tools/processor.jar")),
                "ksp:test");
    }

    private static KspGenerationSettings settings() {
        return new KspGenerationSettings(
                "ksp",
                Optional.of(KSP_VERSION),
                Optional.empty(),
                List.of(new KspProcessorSettings(
                        "com.example:processor", "1.0.0", Optional.empty())),
                Map.of("demo.mode", "strict"));
    }

    private static KspOutputLayout output(Path base) {
        return new KspOutputLayout(
                base,
                base.resolve("cache"),
                base.resolve("classes"),
                base.resolve("kotlin"),
                base.resolve("java"),
                base.resolve("resources"));
    }

    private static JdkStatus jdk(Path root, String version) {
        return new JdkStatus(
                Optional.of(root.resolve("jdk")),
                Optional.of(root.resolve("jdk/bin/java")),
                Optional.of(root.resolve("jdk/bin/javac")),
                Optional.of(root.resolve("jdk/bin/jar")),
                Optional.of(version),
                "8");
    }
}
