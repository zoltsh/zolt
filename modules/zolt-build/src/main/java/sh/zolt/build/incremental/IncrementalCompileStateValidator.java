package sh.zolt.build.incremental;

import static sh.zolt.build.incremental.IncrementalCompileInputHasher.hashText;
import static sh.zolt.build.incremental.IncrementalCompileInputHasher.relative;

import sh.zolt.classpath.Classpath;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class IncrementalCompileStateValidator {
    String validate(
            IncrementalCompileState state,
            String scope,
            Path projectRoot,
            ProjectConfig config,
            List<String> configuredSourceRoots,
            List<GeneratedSourceStep> generatedSteps,
            Classpath compileClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        if (!state.scope().equals(scope)) {
            return "state-scope-mismatch";
        }
        if (!state.projectDirectory().equals(projectRoot)
                || !state.outputDirectory().equals(outputDirectory.toAbsolutePath().normalize())
                || !state.generatedSourcesDirectory().equals(generatedSourcesDirectory.toAbsolutePath().normalize())) {
            return "state-path-mismatch";
        }
        if (!state.compilerSettingsHash().equals(hashText(config.compilerSettings().toString()))) {
            return "compiler-settings-changed";
        }
        if (!state.sourceRoots().equals(sourceRoots(projectRoot, configuredSourceRoots, generatedSteps))) {
            return "source-roots-changed";
        }
        if (!state.generatedSourceRoots().equals(generatedSteps.stream().map(GeneratedSourceStep::output).sorted().toList())) {
            return "generated-source-roots-changed";
        }
        if (!classpathCurrent(state.compileClasspath(), compileClasspath)) {
            return "compile-classpath-changed";
        }
        return validateOutputClasses(state, projectRoot, outputDirectory);
    }

    private static String validateOutputClasses(IncrementalCompileState state, Path projectRoot, Path outputDirectory) {
        List<Path> recorded = state.classes().stream()
                .map(IncrementalCompileState.ClassRecord::outputPath)
                .sorted()
                .toList();
        List<Path> actual;
        if (!Files.isDirectory(outputDirectory)) {
            actual = List.of();
        } else {
            try (Stream<Path> paths = Files.walk(outputDirectory)) {
                actual = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".class"))
                        .map(path -> path.toAbsolutePath().normalize())
                        .sorted()
                        .toList();
            } catch (IOException e) {
                return "output-classes-unreadable";
            }
        }
        if (recorded.equals(actual)) {
            return "";
        }
        List<Path> missing = new ArrayList<>(recorded);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            return "missing-expected-class:" + relative(projectRoot, missing.getFirst());
        }
        List<Path> untracked = new ArrayList<>(actual);
        untracked.removeAll(recorded);
        return "untracked-class:" + relative(projectRoot, untracked.getFirst());
    }

    private static List<String> sourceRoots(
            Path projectRoot,
            List<String> configuredSourceRoots,
            List<GeneratedSourceStep> generatedSteps) {
        List<Path> roots = new ArrayList<>();
        configuredSourceRoots.stream()
                .map(root -> projectRoot.resolve(root).normalize())
                .forEach(roots::add);
        generatedSteps.stream()
                .map(step -> projectRoot.resolve(step.output()).normalize())
                .forEach(roots::add);
        return roots.stream()
                .distinct()
                .sorted()
                .map(path -> relative(projectRoot, path))
                .toList();
    }

    private static boolean classpathCurrent(
            List<IncrementalCompileState.ClasspathEntry> recorded,
            Classpath classpath) {
        List<Path> currentPaths = classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .toList();
        if (!recorded.stream().map(IncrementalCompileState.ClasspathEntry::path).toList().equals(currentPaths)) {
            return false;
        }
        return recorded.stream().allMatch(IncrementalCompileInputHasher::classpathEntryCurrent);
    }
}
