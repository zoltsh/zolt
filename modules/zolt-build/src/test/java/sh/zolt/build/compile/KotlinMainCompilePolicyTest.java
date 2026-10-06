package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolSettings;
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

final class KotlinMainCompilePolicyTest {
    static final Path KOTLIN = Path.of("src/main/kotlin/com/example/Main.kt");

    @Test
    void acceptsKotlinOnlyMainSourcesAndBuildsStableModuleName() {
        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(CompilerSettings.defaults(), Map.of(), Map.of(), "9-demo.app"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertEquals("21", options.release());
        assertEquals("zolt_9_demo_app_main", options.moduleName());
        assertFalse(options.hostPlatformApi());
        assertTrue(options.useJdkRelease());
        assertFalse(options.policy().jvmInterop().javaParameters());
    }

    @Test
    void preservesExplicitMainModuleIdentity() {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "",
                "UTF-8",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_RELEASE,
                "",
                "",
                "2.4.20",
                "maven-artifact",
                "",
                Set.of());

        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(compiler, Map.of(), Map.of(), "different-zolt-name"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertEquals("maven-artifact", options.moduleName());
    }

    @Test
    void hostApiModeIsPreserved() {
        CompilerSettings settings = new CompilerSettings(
                null,
                null,
                "8",
                "UTF-8",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_HOST,
                "");

        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(settings, Map.of(), Map.of(), "demo"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertEquals("8", options.release());
        assertTrue(options.hostPlatformApi());
        assertFalse(options.useJdkRelease());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).hostPlatformApi());
        assertFalse(KotlinCompileOptionsPolicy.javacOptions(options).useJdkRelease());
    }

    @Test
    void releaseApiOnJdkEightUsesTheExactSelectedJdkWithoutUnsupportedFlag() {
        CompilerSettings settings = new CompilerSettings(
                null,
                null,
                "8",
                "UTF-8",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_RELEASE,
                "");

        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(settings, Map.of(), Map.of(), "demo"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("1.8.0_452", "8"));

        assertFalse(options.hostPlatformApi());
        assertFalse(options.useJdkRelease());
        assertFalse(KotlinCompileOptionsPolicy.javacOptions(options).hostPlatformApi());
        assertFalse(KotlinCompileOptionsPolicy.javacOptions(options).useJdkRelease());
    }

    @Test
    void acceptsJavaCompositionWithDeterministicJavacOptions() {
        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                sources(List.of(Path.of("src/main/java/Main.java")), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        JavacOptions javac = KotlinCompileOptionsPolicy.javacOptions(options);

        assertEquals("21", javac.release());
        assertEquals("UTF-8", javac.encoding());
        assertEquals(List.of(), javac.arguments());
        assertEquals(List.of(), javac.modulePath());
        assertFalse(javac.hostPlatformApi());
        assertTrue(javac.useJdkRelease());
        assertFalse(options.policy().jvmInterop().javaParameters());
    }

    @Test
    void acceptsJavaParametersAndMapsItToBothCompilerPhases() {
        CompilerSettings compiler = new CompilerSettings(
                null, null, "", "", List.of("-parameters"), List.of());

        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(compiler, Map.of(), Map.of(), "demo"),
                sources(List.of(Path.of("src/main/java/Main.java")), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertTrue(options.policy().jvmInterop().javaParameters());
        assertEquals(
                List.of("-parameters"),
                KotlinCompileOptionsPolicy.javacOptions(options).arguments());
    }

    @Test
    void rejectsGroovyComposition() {
        KotlinCompileException groovyFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(Path.of("src/main/groovy/Main.groovy")), List.of(KOTLIN)),
                        classpaths(List.of()),
                        jdkStatus("21.0.11", "21")));

        assertTrue(groovyFailure.getMessage().contains("also contains Groovy"));
    }

    @Test
    void rejectsModuleInfoInJointSources() {
        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(Path.of("src/main/java/module-info.java")), List.of(), List.of(KOTLIN)),
                        classpaths(List.of()),
                        jdkStatus("21.0.11", "21")));

        assertTrue(failure.getMessage().contains("module-info.java"));
    }

    @Test
    void acceptsProcessorsForVerifiedKaptExecution() {
        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of(Path.of("processor.jar"))),
                jdkStatus("21.0.11", "21"));

        assertEquals("21", options.release());
    }

    @Test
    void acceptsDeclaredAndOwnedGeneratedJavaSources() {
        for (GeneratedSourceKind kind : List.of(
                GeneratedSourceKind.DECLARED_ROOT,
                GeneratedSourceKind.OPENAPI,
                GeneratedSourceKind.PROTOBUF)) {
            KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                    configWithGeneratedStep(generatedStep(kind)),
                    sources(
                            List.of(Path.of("generated/main/com/example/Generated.java")),
                            List.of(),
                            List.of(KOTLIN)),
                    classpaths(List.of()),
                    jdkStatus("21.0.11", "21"));

            assertEquals("21", options.release());
        }

        KotlinCompilerOptions exec = KotlinMainCompilePolicy.options(
                configWithGeneratedStep(execStep(ProducesLane.JAVA_SOURCES)),
                sources(
                        List.of(Path.of("generated/main/com/example/Generated.java")),
                        List.of(),
                        List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        for (ProducesLane lane : List.of(ProducesLane.RESOURCES, ProducesLane.INTERMEDIATE)) {
            KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                    configWithGeneratedStep(execStep(lane)),
                    sources(List.of(), List.of(), List.of(KOTLIN)),
                    classpaths(List.of()),
                    jdkStatus("21.0.11", "21"));

            assertEquals("21", options.release());
        }
        assertEquals("21", exec.release());
    }

    @Test
    void acceptsOwnedKotlinExecGeneration() {
        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                configWithGeneratedStep(execStep("kotlin", ProducesLane.JAVA_SOURCES)),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertEquals("21", options.release());
    }

    @Test
    void acceptsOwnedKotlinTypedGeneratorOutputs() {
        for (GeneratedSourceKind kind : List.of(GeneratedSourceKind.OPENAPI, GeneratedSourceKind.PROTOBUF)) {
            KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                    configWithGeneratedStep(generatedStep(kind, "kotlin")),
                    sources(List.of(), List.of(), List.of(KOTLIN)),
                    classpaths(List.of()),
                    jdkStatus("21.0.11", "21"));

            assertEquals("21", options.release());
        }
    }

    @Test
    void rejectsMixedCompilationWithoutJavac() {
        JdkStatus runtimeOnly = new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.empty(),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(Path.of("src/main/java/Main.java")), List.of(), List.of(KOTLIN)),
                        classpaths(List.of()),
                        runtimeOnly));

        assertTrue(failure.getMessage().contains("no javac executable"));
    }

    @Test
    void rejectsKaptWithoutJavac() {
        JdkStatus runtimeOnly = new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.empty(),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(), List.of(KOTLIN)),
                        classpaths(List.of(Path.of("processor.jar"))),
                        runtimeOnly));

        assertTrue(failure.getMessage().contains("annotation processing needs javac"));
    }

    @Test
    void acceptsApiAndImplementationWorkspaceDependencies() {
        KotlinCompilerOptions options = KotlinMainCompilePolicy.options(
                config(
                        CompilerSettings.defaults(),
                        Map.of("api", "../api"),
                        Map.of("implementation", "../implementation"),
                        "demo"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertEquals("demo_main", options.moduleName());
    }

    @Test
    void rejectsNonUtf8Sources() {
        CompilerSettings latin1 = new CompilerSettings(
                null, null, "", "ISO-8859-1", List.of(), List.of());
        KotlinCompileException encodingFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(latin1, Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(), List.of(KOTLIN)),
                        classpaths(List.of()),
                        jdkStatus("21.0.11", "21")));

        assertTrue(encodingFailure.getMessage().contains("not UTF-8"));
    }

    @Test
    void rejectsReleaseNewerThanSelectedJdkAndIncompleteJdk() {
        KotlinCompileException releaseFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(), List.of(KOTLIN)),
                        classpaths(List.of()),
                        jdkStatus("17.0.12", "17")));
        JdkStatus incomplete = new JdkStatus(
                Optional.empty(),
                Optional.of(Path.of("/jdk/bin/java")),
                Optional.of(Path.of("/jdk/bin/javac")),
                Optional.of(Path.of("/jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
        KotlinCompileException jdkFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(), List.of(KOTLIN)),
                        classpaths(List.of()),
                        incomplete));

        assertTrue(releaseFailure.getMessage().contains("newer than the selected JDK"));
        assertTrue(jdkFailure.getMessage().contains("no complete Java runtime home"));
    }

    static SourceDiscoveryResult sources(
            List<Path> java,
            List<Path> groovy,
            List<Path> kotlin) {
        return new SourceDiscoveryResult(java, groovy, kotlin, List.of(), List.of(), List.of());
    }

    static ProjectConfig config(
            CompilerSettings compiler,
            Map<String, String> workspaceApiDependencies,
            Map<String, String> workspaceDependencies,
            String name) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata(name, "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                workspaceApiDependencies,
                Map.of(),
                Set.of(),
                workspaceDependencies,
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                NativeSettings.defaults(),
                compiler,
                PackageSettings.defaults());
    }

    private static ProjectConfig configWithGeneratedStep(GeneratedSourceStep step) {
        ProjectConfig config = config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo");
        return config.withBuildSettings(config.build().withGeneratedSources(List.of(step), List.of()));
    }

    private static GeneratedSourceStep generatedStep(GeneratedSourceKind kind) {
        return generatedStep(kind, "java");
    }

    private static GeneratedSourceStep generatedStep(GeneratedSourceKind kind, String language) {
        return new GeneratedSourceStep(
                "generated",
                kind,
                language,
                "generated/main",
                List.of(),
                true,
                false);
    }

    private static GeneratedSourceStep execStep(ProducesLane lane) {
        return execStep("java", lane);
    }

    private static GeneratedSourceStep execStep(String language, ProducesLane lane) {
        return new GeneratedSourceStep(
                "generate",
                GeneratedSourceKind.EXEC,
                language,
                "target/generated/generate",
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

    static ClasspathSet classpaths(List<Path> processors) {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(
                empty,
                empty,
                empty,
                empty,
                new Classpath(processors),
                empty,
                empty);
    }

    static JdkStatus jdkStatus(String version, String feature) {
        return new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of(version),
                feature);
    }
}
