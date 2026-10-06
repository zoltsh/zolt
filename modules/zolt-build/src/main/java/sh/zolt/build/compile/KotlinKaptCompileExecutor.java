package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.Classpath;
import sh.zolt.doctor.JdkStatus;

/** Runs KAPT generation, ordinary Kotlin compilation, and non-processing javac in order. */
public final class KotlinKaptCompileExecutor {
    private final JavacRunner javacRunner;
    private final KotlinCompilerRunner kotlinCompilerRunner;

    public KotlinKaptCompileExecutor(
            JavacRunner javacRunner,
            KotlinCompilerRunner kotlinCompilerRunner) {
        this.javacRunner = javacRunner;
        this.kotlinCompilerRunner = kotlinCompilerRunner;
    }

    public JavacResult compile(
            JdkStatus jdkStatus,
            List<Path> allSources,
            List<Path> javaSources,
            Classpath compilerLauncherClasspath,
            Classpath compilationClasspath,
            Classpath processorClasspath,
            Path kaptPluginJar,
            Path outputDirectory,
            Path generatedSourcesDirectory,
            KotlinCompilerOptions kotlinOptions,
            KotlinCompilationScope scope) {
        createGeneratedSourcesDirectory(generatedSourcesDirectory, scope);
        try (KotlinKaptStubsDirectory stubs = KotlinKaptStubsDirectory.create()) {
            KotlinKaptOptions kaptOptions = new KotlinKaptOptions(
                    kaptPluginJar,
                    processorClasspath,
                    generatedSourcesDirectory,
                    outputDirectory,
                    stubs.path());
            JavacResult kapt = kotlinCompilerRunner.compile(
                    jdkStatus.java().orElseThrow(),
                    jdkStatus.javaHome().orElseThrow(),
                    allSources,
                    compilerLauncherClasspath,
                    compilationClasspath,
                    outputDirectory,
                    kotlinOptions,
                    scope,
                    kaptOptions);
            List<Path> generatedJavaSources = generatedJavaSources(
                    generatedSourcesDirectory,
                    scope);
            JavacResult kotlin = kotlinCompilerRunner.compile(
                    jdkStatus.java().orElseThrow(),
                    jdkStatus.javaHome().orElseThrow(),
                    combinedSources(allSources, generatedJavaSources),
                    compilerLauncherClasspath,
                    compilationClasspath,
                    outputDirectory,
                    kotlinOptions,
                    scope);
            JavacResult java = javacRunner.compile(
                    jdkStatus.javac().orElseThrow(),
                    combinedSources(javaSources, generatedJavaSources),
                    javacClasspath(outputDirectory, compilationClasspath),
                    outputDirectory,
                    new Classpath(List.of()),
                    null,
                    KotlinCompileOptionsPolicy.javacOptions(kotlinOptions));
            return new JavacResult(
                    allSources.size(),
                    outputDirectory,
                    IncrementalJavacExecution.combinedOutput(
                            kapt.output(),
                            IncrementalJavacExecution.combinedOutput(
                                    kotlin.output(), java.output())));
        }
    }

    private static void createGeneratedSourcesDirectory(
            Path directory,
            KotlinCompilationScope scope) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not create the KAPT generated " + scope.label() + "-source directory " + directory
                            + ". Check that the project directory is writable.",
                    exception);
        }
    }

    private static List<Path> generatedJavaSources(
            Path directory,
            KotlinCompilationScope scope) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(Path::normalize)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not inspect KAPT generated " + scope.label() + " Java sources under " + directory
                            + ". Check that the generated-source directory is readable.",
                    exception);
        }
    }

    private static List<Path> combinedSources(
            List<Path> authored,
            List<Path> generated) {
        List<Path> combined = new ArrayList<>(authored.size() + generated.size());
        combined.addAll(authored);
        combined.addAll(generated);
        return List.copyOf(combined);
    }

    private static Classpath javacClasspath(
            Path outputDirectory,
            Classpath compilationClasspath) {
        List<Path> entries = new ArrayList<>();
        entries.add(outputDirectory);
        entries.addAll(compilationClasspath.entries());
        return new Classpath(entries);
    }
}
