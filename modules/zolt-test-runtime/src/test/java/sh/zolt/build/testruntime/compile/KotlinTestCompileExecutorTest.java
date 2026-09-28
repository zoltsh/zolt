package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.JavacResult;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompilerRunner;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;

final class KotlinTestCompileExecutorTest {
    private static final Path OUTPUT = Path.of("target/test-classes");
    private static final Path JAVA_TEST = Path.of("src/test/java/p/JavaCycleTest.java");
    private static final Path KOTLIN_TEST = Path.of("src/test/kotlin/p/KotlinCycleTest.kt");

    @Test
    void compilesAllSourcesWithKotlinThenOnlyJavaWithOutputFirst() {
        List<String> phases = new ArrayList<>();
        Classpath compileClasspath = new Classpath(List.of(
                Path.of("target/classes"), Path.of("lib/test.jar")));
        Classpath launcherClasspath = new Classpath(List.of(Path.of("lib/kotlin-compiler.jar")));
        KotlinCompilerRunner.Options options = new KotlinCompilerRunner.Options(
                "8", "demo_test", false, false, Path.of("target/classes"));
        CompileDiagnostics diagnostics = new CompileDiagnostics(1, 2, 3, 4, 5, 6, 7, 8);
        KotlinTestCompileExecutor executor = new KotlinTestCompileExecutor(
                (javac, sources, classpath, output, processors, generated, javacOptions) -> {
                    phases.add("javac");
                    assertEquals(Path.of("/jdk/bin/javac"), javac);
                    assertEquals(List.of(JAVA_TEST), sources);
                    assertEquals(
                            List.of(OUTPUT, Path.of("target/classes"), Path.of("lib/test.jar")),
                            classpath.entries());
                    assertTrue(processors.entries().isEmpty());
                    assertNull(generated);
                    assertEquals("8", javacOptions.release());
                    assertEquals("UTF-8", javacOptions.encoding());
                    assertFalse(javacOptions.useJdkRelease());
                    return new JavacResult(1, output, "javac output");
                },
                (java, jdkHome, sources, launcher, classpath, output, kotlinOptions, scope) -> {
                    phases.add("kotlinc");
                    assertEquals(Path.of("/jdk/bin/java"), java);
                    assertEquals(Path.of("/jdk"), jdkHome);
                    assertEquals(List.of(JAVA_TEST, KOTLIN_TEST), sources);
                    assertEquals(launcherClasspath, launcher);
                    assertEquals(compileClasspath, classpath);
                    assertEquals(options, kotlinOptions);
                    assertEquals(KotlinCompilationScope.TEST, scope);
                    return new JavacResult(2, output, "kotlin output");
                });

        TestCompileAttempt attempt = executor.compile(
                jdkStatus(),
                sources(List.of(JAVA_TEST), List.of(KOTLIN_TEST)),
                compileClasspath,
                launcherClasspath,
                options,
                OUTPUT,
                "kotlin-test-sources",
                diagnostics);

        assertEquals(List.of("kotlinc", "javac"), phases);
        assertEquals(2, attempt.sourceCount());
        assertEquals("kotlin output\njavac output", attempt.output());
        assertEquals(List.of(JAVA_TEST, KOTLIN_TEST), attempt.compiledSources());
        assertEquals(diagnostics, attempt.diagnostics());
        assertEquals("kotlin-test-sources", attempt.fallbackReason());
    }

    @Test
    void kotlinFailurePreventsJavac() {
        AtomicBoolean javacCalled = new AtomicBoolean();
        KotlinTestCompileExecutor executor = new KotlinTestCompileExecutor(
                (javac, sources, classpath, output, processors, generated, options) -> {
                    javacCalled.set(true);
                    return new JavacResult(1, output, "");
                },
                (java, home, sources, launcher, classpath, output, options, scope) -> {
                    throw new KotlinCompileException("failed Kotlin phase");
                });

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> executor.compile(
                        jdkStatus(),
                        sources(List.of(JAVA_TEST), List.of(KOTLIN_TEST)),
                        new Classpath(List.of()),
                        new Classpath(List.of()),
                        new KotlinCompilerRunner.Options("21", "demo_test", false),
                        OUTPUT,
                        "",
                        CompileDiagnostics.empty()));

        assertEquals("failed Kotlin phase", failure.getMessage());
        assertFalse(javacCalled.get());
    }

    @Test
    void pureKotlinTestsDoNotLaunchJavac() {
        AtomicBoolean javacCalled = new AtomicBoolean();
        KotlinTestCompileExecutor executor = new KotlinTestCompileExecutor(
                (javac, sources, classpath, output, processors, generated, options) -> {
                    javacCalled.set(true);
                    return new JavacResult(0, output, "");
                },
                (java, home, sources, launcher, classpath, output, options, scope) ->
                        new JavacResult(1, output, "kotlin output"));

        TestCompileAttempt attempt = executor.compile(
                jdkStatus(),
                sources(List.of(), List.of(KOTLIN_TEST)),
                new Classpath(List.of()),
                new Classpath(List.of()),
                new KotlinCompilerRunner.Options("21", "demo_test", false),
                OUTPUT,
                "",
                CompileDiagnostics.empty());

        assertFalse(javacCalled.get());
        assertEquals(1, attempt.sourceCount());
        assertEquals("kotlin output", attempt.output());
    }

    private static SourceDiscoveryResult sources(
            List<Path> javaTests,
            List<Path> kotlinTests) {
        return new SourceDiscoveryResult(
                List.of(), List.of(), List.of(), javaTests, List.of(), kotlinTests);
    }

    private static JdkStatus jdkStatus() {
        return new JdkStatus(
                Optional.of(Path.of("/jdk")),
                Optional.of(Path.of("/jdk/bin/java")),
                Optional.of(Path.of("/jdk/bin/javac")),
                Optional.of(Path.of("/jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
    }
}
