package sh.zolt.build.testruntime.compile;

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
import sh.zolt.build.compile.KotlinCompilerRunner;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
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
                jdkStatus());

        assertEquals("21", options.release());
        assertEquals("demo_test", options.moduleName());
        assertFalse(options.hostPlatformApi());
        assertTrue(options.useJdkRelease());
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
                jdkStatus());

        assertEquals("demo_test", options.moduleName());
    }

    @Test
    void rejectsProcessorsAndCustomJavacTestArguments() {
        KotlinCompileException processorFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinTestCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), Map.of()),
                        sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                        classpaths(List.of(Path.of("processor.jar"))),
                        jdkStatus()));
        CompilerSettings arguments = new CompilerSettings(
                null, null, "", "", List.of(), List.of("-parameters"));
        KotlinCompileException argumentsFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinTestCompilePolicy.options(
                        config(arguments, Map.of(), Map.of(), Map.of()),
                        sources(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(KOTLIN_TEST)),
                        classpaths(List.of()),
                        jdkStatus()));

        assertTrue(processorFailure.getMessage().contains("[dependencies.test-processor]"));
        assertTrue(argumentsFailure.getMessage().contains("[compiler].testArgs"));
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
                        jdkStatus()));

        assertTrue(failure.getMessage().contains("Quarkus is enabled"));
        assertTrue(failure.getMessage().contains("explicit Kotlin test roots"));
    }

    private static SourceDiscoveryResult sources(
            List<Path> javaMain,
            List<Path> groovyMain,
            List<Path> kotlinMain,
            List<Path> javaTest,
            List<Path> groovyTest,
            List<Path> kotlinTest) {
        return new SourceDiscoveryResult(
                javaMain, groovyMain, kotlinMain, javaTest, groovyTest, kotlinTest);
    }

    private static ProjectConfig config(
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

    private static ClasspathSet classpaths(List<Path> testProcessors) {
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

    private static JdkStatus jdkStatus() {
        return new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
    }
}
