package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.JavacOptions;
import sh.zolt.build.compile.KotlinCompileOptionsPolicy;
import sh.zolt.build.compile.KotlinCompilerRunner;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProtobufGenerationSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.QuarkusSettings;

final class KotlinTestCompilePolicyTest {
    private static final Path KOTLIN_TEST = Path.of("src/test/kotlin/com/example/DemoTest.kt");

    @Test
    void acceptsKotlinOnlyTestsWithKotlinMainAndBuildsTestModuleName() {
        KotlinCompilerRunner.Options options = KotlinTestCompilePolicy.options(
                config(CompilerSettings.defaults(), Map.of(), Map.of(), Map.of()),
                sources(List.of(), List.of(), List.of(Path.of("src/main/kotlin/com/example/Demo.kt")),
                        List.of(), List.of(), List.of(KOTLIN_TEST)),
                classpaths(List.of()),
                jdkStatus(),
                Path.of("target/classes"));

        assertEquals("21", options.release());
        assertEquals("demo_test", options.moduleName());
        assertFalse(options.hostPlatformApi());
        assertTrue(options.useJdkRelease());
        assertFalse(options.javaParameters());
        assertFalse(options.warningsAsErrors());
        assertEquals(Path.of("target/classes"), options.friendPath());
    }

    @Test
    void acceptsWorkspaceApiImplementationAndTestDependencies() {
        KotlinCompilerRunner.Options options = KotlinTestCompilePolicy.options(
                config(
                        CompilerSettings.defaults(),
                        Map.of("api", "../api"),
                        Map.of("implementation", "../implementation"),
                        Map.of("test-support", "../test-support")),
                sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                classpaths(List.of()),
                jdkStatus(),
                null);

        assertEquals("demo_test", options.moduleName());
        assertNull(options.friendPath());
    }

    @Test
    void mapsSelectedJdk8ToJvmTargetAndSourceTargetWithoutHostMode() {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "8",
                "",
                List.of(),
                List.of());
        KotlinCompilerRunner.Options options = KotlinTestCompilePolicy.options(
                config(compiler, Map.of(), Map.of(), Map.of()),
                sources(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(Path.of("src/test/java/com/example/DemoTest.java")),
                        List.of(),
                        List.of(KOTLIN_TEST)),
                classpaths(List.of()),
                jdkStatus("1.8.0_412", "8"),
                null);

        JavacOptions javac = KotlinCompileOptionsPolicy.javacOptions(options);

        assertEquals("8", options.release());
        assertFalse(options.hostPlatformApi());
        assertFalse(options.useJdkRelease());
        assertFalse(javac.hostPlatformApi());
        assertFalse(javac.useJdkRelease());
    }

    @Test
    void mapsExplicitTestHostModeWithoutJdkRelease() {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "",
                "",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_RELEASE,
                CompilerSettings.PLATFORM_API_HOST,
                "",
                "");
        KotlinCompilerRunner.Options options = KotlinTestCompilePolicy.options(
                config(compiler, Map.of(), Map.of(), Map.of()),
                sources(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(Path.of("src/test/java/com/example/DemoTest.java")),
                        List.of(),
                        List.of(KOTLIN_TEST)),
                classpaths(List.of()),
                jdkStatus(),
                null);

        JavacOptions javac = KotlinCompileOptionsPolicy.javacOptions(options);

        assertEquals("21", options.release());
        assertTrue(options.hostPlatformApi());
        assertFalse(options.useJdkRelease());
        assertTrue(javac.hostPlatformApi());
        assertFalse(javac.useJdkRelease());
    }

    @Test
    void rejectsModuleInfoAndMissingJavacForMixedTests() {
        KotlinCompileException moduleFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinTestCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), Map.of()),
                        sources(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(Path.of("src/test/java/module-info.java")),
                                List.of(),
                                List.of(KOTLIN_TEST)),
                        classpaths(List.of()),
                        jdkStatus(),
                        null));
        JdkStatus runtimeOnly = new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.empty(),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
        KotlinCompileException javacFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinTestCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), Map.of()),
                        sources(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(Path.of("src/test/java/com/example/DemoTest.java")),
                                List.of(),
                                List.of(KOTLIN_TEST)),
                        classpaths(List.of()),
                        runtimeOnly,
                        null));

        assertTrue(moduleFailure.getMessage().contains("module-info.java"));
        assertTrue(javacFailure.getMessage().contains("no javac executable"));
    }

    @Test
    void acceptsDeclaredJavaRootsButRejectsOwnedJavaGeneration() {
        KotlinCompilerRunner.Options declaredRoot = KotlinTestCompilePolicy.options(
                configWithGeneratedTestStep(generatedStep(GeneratedSourceKind.DECLARED_ROOT)),
                sources(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(Path.of("generated/test/com/example/DeclaredTest.java")),
                        List.of(),
                        List.of(KOTLIN_TEST)),
                classpaths(List.of()),
                jdkStatus(),
                null);

        for (GeneratedSourceKind kind : List.of(GeneratedSourceKind.OPENAPI, GeneratedSourceKind.PROTOBUF)) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> KotlinTestCompilePolicy.options(
                            configWithGeneratedTestStep(generatedStep(kind)),
                            sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                            classpaths(List.of()),
                            jdkStatus(),
                            null));

            assertTrue(failure.getMessage().contains("owned Java test-source generation"));
            assertTrue(failure.getMessage().contains("kind = \"declared-root\""));
        }

        for (ProducesLane lane : List.of(ProducesLane.JAVA_SOURCES, ProducesLane.TEST_SOURCES)) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> KotlinTestCompilePolicy.options(
                            configWithGeneratedTestStep(execStep(lane)),
                            sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                            classpaths(List.of()),
                            jdkStatus(),
                            null));

            assertTrue(failure.getMessage().contains("owned Java test-source generation"));
        }

        for (ProducesLane lane : List.of(ProducesLane.TEST_RESOURCES, ProducesLane.INTERMEDIATE)) {
            KotlinCompilerRunner.Options options = KotlinTestCompilePolicy.options(
                    configWithGeneratedTestStep(execStep(lane)),
                    sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                    classpaths(List.of()),
                    jdkStatus(),
                    null);

            assertEquals("21", options.release());
        }
        assertEquals("21", declaredRoot.release());
    }

    @Test
    void rejectsTestAnnotationProcessors() {
        KotlinCompileException processorFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinTestCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), Map.of()),
                        sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                        classpaths(List.of(Path.of("processor.jar"))),
                        jdkStatus(),
                        null));

        assertTrue(processorFailure.getMessage().contains("[dependencies.test-processor]"));
    }

    @Test
    void rejectsQuarkusUntilItsWorkspaceModelCarriesKotlinTestRoots() {
        ProjectConfig config = config(
                        CompilerSettings.defaults(), Map.of(), Map.of(), Map.of())
                .withFrameworkSettings(new FrameworkSettings(new QuarkusSettings(true, null)));

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinTestCompilePolicy.options(
                        config,
                        sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                        classpaths(List.of()),
                        jdkStatus(),
                        null));

        assertTrue(failure.getMessage().contains("Quarkus is enabled"));
        assertTrue(failure.getMessage().contains("explicit Kotlin test roots"));
    }

    static SourceDiscoveryResult sources(
            List<Path> javaMain,
            List<Path> groovyMain,
            List<Path> kotlinMain,
            List<Path> javaTest,
            List<Path> groovyTest,
            List<Path> kotlinTest) {
        return new SourceDiscoveryResult(
                javaMain, groovyMain, kotlinMain, javaTest, groovyTest, kotlinTest);
    }

    static ProjectConfig config(
            CompilerSettings compiler,
            Map<String, String> workspaceApi,
            Map<String, String> workspaceCompile,
            Map<String, String> workspaceTest) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                workspaceApi,
                Map.of(),
                Set.of(),
                workspaceCompile,
                Map.of(),
                Set.of(),
                workspaceTest,
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                NativeSettings.defaults(),
                compiler,
                PackageSettings.defaults());
    }

    static ClasspathSet classpaths(List<Path> testProcessors) {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(
                empty,
                empty,
                empty,
                empty,
                empty,
                new Classpath(testProcessors),
                empty);
    }

    private static ProjectConfig configWithGeneratedTestStep(GeneratedSourceStep step) {
        ProjectConfig config = config(CompilerSettings.defaults(), Map.of(), Map.of(), Map.of());
        return config.withBuildSettings(config.build().withGeneratedSources(List.of(), List.of(step)));
    }

    private static GeneratedSourceStep generatedStep(GeneratedSourceKind kind) {
        return new GeneratedSourceStep(
                "generated",
                kind,
                "java",
                "generated/test",
                List.of(),
                true,
                false);
    }

    private static GeneratedSourceStep execStep(ProducesLane lane) {
        return new GeneratedSourceStep(
                "generate",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated-test/generate",
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                new ExecGenerationSettings(
                        "generator",
                        ExecToolSettings.empty(),
                        List.of(),
                        lane,
                        Optional.empty(),
                        Map.of(),
                        "content"));
    }

    static JdkStatus jdkStatus() {
        return jdkStatus("21.0.11", "21");
    }

    private static JdkStatus jdkStatus(String version, String requiredVersion) {
        return new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of(version),
                requiredVersion);
    }
}
