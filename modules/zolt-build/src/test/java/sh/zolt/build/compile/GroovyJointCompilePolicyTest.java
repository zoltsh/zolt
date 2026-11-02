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
import sh.zolt.build.GroovyCompileException;
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

final class GroovyJointCompilePolicyTest {
    @Test
    void acceptsMatchingReleaseAndDefaultsEncoding() {
        GroovyCompilerRunner.JointOptions options = GroovyJointCompilePolicy.options(
                config(CompilerSettings.defaults(), Map.of(), Map.of()),
                List.of(Path.of("src/main/java/Main.groovy")),
                classpaths(List.of()),
                jdkStatus("21.0.11"));

        assertEquals("21", options.release());
        assertEquals("UTF-8", options.encoding());
        assertFalse(options.hostPlatformApi());
    }

    @Test
    void rejectsAnnotationProcessors() {
        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> GroovyJointCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of()),
                        List.of(Path.of("src/main/java/Main.groovy")),
                        classpaths(List.of(Path.of("processor.jar"))),
                        jdkStatus("21.0.11")));

        assertTrue(exception.getMessage().contains("[dependencies.processor]"));
    }

    @Test
    void rejectsCustomCompilerArguments() {
        CompilerSettings compiler = new CompilerSettings(
                null, null, "", "", List.of("-parameters"), List.of());

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> GroovyJointCompilePolicy.options(
                        config(compiler, Map.of(), Map.of()),
                        List.of(Path.of("src/main/java/Main.groovy")),
                        classpaths(List.of()),
                        jdkStatus("21.0.11")));

        assertTrue(exception.getMessage().contains("[compiler].args"));
    }

    @Test
    void rejectsModularSourceSets() {
        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> GroovyJointCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of()),
                        List.of(
                                Path.of("src/main/java/module-info.java"),
                                Path.of("src/main/java/Main.groovy")),
                        classpaths(List.of()),
                        jdkStatus("21.0.11")));

        assertTrue(exception.getMessage().contains("module-info.java"));
    }

    @Test
    void rejectsCompileScopedWorkspaceDependencies() {
        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> GroovyJointCompilePolicy.options(
                        config(
                                CompilerSettings.defaults(),
                                Map.of("api", "../api"),
                                Map.of("implementation", "../implementation")),
                        List.of(Path.of("src/main/java/Main.groovy")),
                        classpaths(List.of()),
                        jdkStatus("21.0.11")));

        assertTrue(exception.getMessage().contains("workspace dependencies"));
        assertTrue(exception.getMessage().contains("AST-transform"));
    }

    @Test
    void rejectsNewerJdkInReleaseMode() {
        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> GroovyJointCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of()),
                        List.of(Path.of("src/main/java/Main.groovy")),
                        classpaths(List.of()),
                        jdkStatus("22.0.2")));

        assertTrue(exception.getMessage().contains("selected JDK feature version 22"));
        assertTrue(exception.getMessage().contains("effective Java release 21"));
        assertTrue(exception.getMessage().contains("jdkApi = \"host\""));
    }

    @Test
    void hostPlatformModeExplicitlyAllowsNewerJdk() {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "",
                "UTF-16",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_HOST,
                "");

        GroovyCompilerRunner.JointOptions options = GroovyJointCompilePolicy.options(
                config(compiler, Map.of(), Map.of()),
                List.of(Path.of("src/main/java/Main.groovy")),
                classpaths(List.of()),
                jdkStatus("22.0.2"));

        assertEquals("21", options.release());
        assertEquals("UTF-16", options.encoding());
        assertTrue(options.hostPlatformApi());
    }

    @Test
    void rejectsMissingJavaExecutable() {
        JdkStatus status = new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.empty(),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> GroovyJointCompilePolicy.options(
                        config(CompilerSettings.defaults(), Map.of(), Map.of()),
                        List.of(Path.of("src/main/java/Main.groovy")),
                        classpaths(List.of()),
                        status));

        assertTrue(exception.getMessage().contains("no java executable"));
    }

    private static ProjectConfig config(
            CompilerSettings compiler,
            Map<String, String> workspaceApiDependencies,
            Map<String, String> workspaceDependencies) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata(
                        "demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
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

    private static ClasspathSet classpaths(List<Path> processorEntries) {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(
                empty,
                empty,
                empty,
                empty,
                new Classpath(processorEntries),
                empty,
                empty);
    }

    private static JdkStatus jdkStatus(String version) {
        return new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of(version),
                "21");
    }
}
