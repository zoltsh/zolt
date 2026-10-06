package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProtobufGenerationSettings;

final class KspTestGenerationCoordinatorTest {
    @TempDir
    private Path projectDirectory;

    @Test
    void passesExactTestInputsMainOutputAndOrderedLibrariesToEveryStep() throws IOException {
        source("src/main/java/demo/App.kt");
        Path java = source("src/test/java/demo/AppTest.java");
        Path kotlin = source("src/test/kotlin/demo/AppTest.kt");
        source("target/generated/ksp/test/first/kotlin/demo/Previous.kt");
        Path mainOutput = projectDirectory.resolve("target/classes");
        List<Path> testLibraries = List.of(
                projectDirectory.resolve("lib/second.jar"),
                projectDirectory.resolve("lib/first.jar"));
        List<Invocation> invocations = new ArrayList<>();
        KspTestGenerationCoordinator coordinator = new KspTestGenerationCoordinator(
                new SourceDiscoverer(),
                required -> jdk(),
                (config, packages) -> "2.2.0",
                (root, scope, step, packages, kotlinVersion, jdk, options,
                        kotlinInputs, javaInputs, libraries) -> invocations.add(new Invocation(
                                root,
                                scope,
                                step.id(),
                                kotlinVersion,
                                jdk,
                                options,
                                kotlinInputs,
                                javaInputs,
                                libraries)));

        coordinator.generate(
                projectDirectory,
                config(List.of(step("first"), step("second"))),
                classpaths(testLibraries),
                List.of(),
                mainOutput);

        assertEquals(List.of("first", "second"), invocations.stream().map(Invocation::stepId).toList());
        Invocation first = invocations.getFirst();
        assertEquals(projectDirectory.toAbsolutePath().normalize(), first.root());
        assertEquals("test", first.scope());
        assertEquals("2.2.0", first.kotlinVersion());
        assertEquals(jdk(), first.jdkStatus());
        assertEquals("demo_test", first.compilerOptions().moduleName());
        assertEquals(mainOutput, first.compilerOptions().friendPath());
        assertEquals(List.of(kotlin), first.kotlinInputs());
        assertEquals(List.of(java), first.javaInputs());
        assertEquals(List.of(mainOutput, testLibraries.get(0), testLibraries.get(1)), first.libraries());
    }

    @Test
    void noKspStepsDoNotInspectSourcesOrToolchains() {
        AtomicInteger calls = new AtomicInteger();
        KspTestGenerationCoordinator coordinator = new KspTestGenerationCoordinator(
                new SourceDiscoverer(),
                required -> {
                    calls.incrementAndGet();
                    return jdk();
                },
                (config, packages) -> {
                    calls.incrementAndGet();
                    return "2.2.0";
                },
                (root, scope, step, packages, kotlinVersion, jdk, options,
                        kotlinInputs, javaInputs, libraries) -> calls.incrementAndGet());

        coordinator.generate(
                projectDirectory,
                config(List.of()),
                classpaths(List.of()),
                List.of(),
                projectDirectory.resolve("target/classes"));

        assertEquals(0, calls.get());
    }

    @Test
    void rejectsKspWithoutKotlinTestsBeforeResolvingToolchains() throws IOException {
        source("src/test/java/demo/AppTest.java");
        AtomicInteger resolutions = new AtomicInteger();
        KspTestGenerationCoordinator coordinator = new KspTestGenerationCoordinator(
                new SourceDiscoverer(),
                required -> jdk(),
                (config, packages) -> {
                    resolutions.incrementAndGet();
                    return "2.2.0";
                },
                (root, scope, step, packages, kotlinVersion, jdk, options,
                        kotlinInputs, javaInputs, libraries) -> {
                    throw new AssertionError("KSP must not run");
                });

        BuildException failure = assertThrows(
                BuildException.class,
                () -> coordinator.generate(
                        projectDirectory,
                        config(List.of(step("symbols"))),
                        classpaths(List.of()),
                        List.of(),
                        projectDirectory.resolve("target/classes")));

        assertTrue(failure.getMessage().contains("requires at least one Kotlin test source"));
        assertEquals(0, resolutions.get());
    }

    private static ProjectConfig config(List<GeneratedSourceStep> steps) {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "21",
                "UTF-8",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_RELEASE,
                "",
                "",
                "2.2.0",
                "",
                "");
        BuildSettings defaults = BuildSettings.defaults();
        BuildSettings build = new BuildSettings(
                defaults.source(),
                defaults.sourceRoots(),
                defaults.test(),
                defaults.outputRoot(),
                defaults.output(),
                defaults.testOutput(),
                defaults.testSources(),
                defaults.groovyTestSources(),
                List.of("src/test/kotlin"),
                defaults.resourceRoots(),
                defaults.testResourceRoots(),
                defaults.metadata()).withGeneratedSources(List.of(), steps);
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                build,
                NativeSettings.defaults(),
                compiler,
                PackageSettings.defaults());
    }

    private static GeneratedSourceStep step(String id) {
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.KSP,
                "kotlin",
                "target/generated/ksp/test/" + id,
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                new KspGenerationSettings(
                        "ksp-" + id,
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:" + id, "1.0.0", Optional.empty())),
                        Map.of()));
    }

    private static ClasspathSet classpaths(List<Path> testCompileEntries) {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(
                empty,
                empty,
                empty,
                new Classpath(testCompileEntries),
                empty,
                empty,
                empty);
    }

    private static JdkStatus jdk() {
        return new JdkStatus(
                Optional.of(Path.of("/jdk")),
                Optional.of(Path.of("/jdk/bin/java")),
                Optional.of(Path.of("/jdk/bin/javac")),
                Optional.of(Path.of("/jdk/bin/jar")),
                Optional.of("21.0.11"),
                "21");
    }

    private Path source(String relative) throws IOException {
        Path source = projectDirectory.resolve(relative).normalize();
        Files.createDirectories(source.getParent());
        Files.writeString(source, "fixture\n");
        return source;
    }

    private record Invocation(
            Path root,
            String scope,
            String stepId,
            String kotlinVersion,
            JdkStatus jdkStatus,
            KotlinCompilerOptions compilerOptions,
            List<Path> kotlinInputs,
            List<Path> javaInputs,
            List<Path> libraries) {
    }
}
