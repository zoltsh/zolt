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
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class KotlinMainCompilePolicyTest {
    private static final Path KOTLIN = Path.of("src/main/kotlin/com/example/Main.kt");

    @Test
    void acceptsKotlinOnlyMainSourcesAndBuildsStableModuleName() {
        KotlinCompilerRunner.Options options = KotlinMainCompilePolicy.options(
                config(CompilerSettings.defaults(), Map.of(), Map.of(), "9-demo.app"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertEquals("21", options.release());
        assertEquals("zolt_9_demo_app_main", options.moduleName());
        assertFalse(options.hostPlatformApi());
        assertTrue(options.useJdkRelease());
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

        KotlinCompilerRunner.Options options = KotlinMainCompilePolicy.options(
                config(settings, Map.of(), Map.of(), "demo"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("21.0.11", "21"));

        assertEquals("8", options.release());
        assertTrue(options.hostPlatformApi());
        assertFalse(options.useJdkRelease());
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

        KotlinCompilerRunner.Options options = KotlinMainCompilePolicy.options(
                config(settings, Map.of(), Map.of(), "demo"),
                sources(List.of(), List.of(), List.of(KOTLIN)),
                classpaths(List.of()),
                jdkStatus("1.8.0_452", "8"));

        assertFalse(options.hostPlatformApi());
        assertFalse(options.useJdkRelease());
    }

    @Test
    void rejectsJavaAndGroovyComposition() {
        KotlinCompileException javaFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(Path.of("src/main/java/Main.java")), List.of(), List.of(KOTLIN)),
                        classpaths(List.of()),
                        jdkStatus("21.0.11", "21")));
        KotlinCompileException groovyFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(Path.of("src/main/groovy/Main.groovy")), List.of(KOTLIN)),
                        classpaths(List.of()),
                        jdkStatus("21.0.11", "21")));

        assertTrue(javaFailure.getMessage().contains("also contains Java"));
        assertTrue(groovyFailure.getMessage().contains("also contains Groovy"));
    }

    @Test
    void rejectsProcessorsAndJavacArguments() {
        KotlinCompileException processorFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(), List.of(KOTLIN)),
                        classpaths(List.of(Path.of("processor.jar"))),
                        jdkStatus("21.0.11", "21")));
        CompilerSettings arguments = new CompilerSettings(
                null, null, "", "", List.of("-parameters"), List.of());
        KotlinCompileException argumentFailure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinMainCompilePolicy.options(
                        config(arguments, Map.of(), Map.of(), "demo"),
                        sources(List.of(), List.of(), List.of(KOTLIN)),
                        classpaths(List.of()),
                        jdkStatus("21.0.11", "21")));

        assertTrue(processorFailure.getMessage().contains("[dependencies.processor]"));
        assertTrue(argumentFailure.getMessage().contains("[compiler].args"));
    }

    @Test
    void acceptsApiAndImplementationWorkspaceDependencies() {
        KotlinCompilerRunner.Options options = KotlinMainCompilePolicy.options(
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

    private static SourceDiscoveryResult sources(
            List<Path> java,
            List<Path> groovy,
            List<Path> kotlin) {
        return new SourceDiscoveryResult(java, groovy, kotlin, List.of(), List.of(), List.of());
    }

    private static ProjectConfig config(
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

    private static ClasspathSet classpaths(List<Path> processors) {
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

    private static JdkStatus jdkStatus(String version, String feature) {
        return new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of(version),
                feature);
    }
}
