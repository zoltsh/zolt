package sh.zolt.ide;

import sh.zolt.classpath.Classpath;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class IdeClasspathModelBuilder {
    private final ZoltLockfileReader lockfileReader;
    private final ClasspathBuilder classpathBuilder;

    IdeClasspathModelBuilder() {
        this(new ZoltLockfileReader(), new ClasspathBuilder());
    }

    IdeClasspathModelBuilder(ZoltLockfileReader lockfileReader, ClasspathBuilder classpathBuilder) {
        this.lockfileReader = lockfileReader;
        this.classpathBuilder = classpathBuilder;
    }

    IdeModel.ClasspathInfo build(
            Path lockfilePath,
            Path cacheRoot,
            Path root,
            ProjectConfig config,
            List<IdeModel.Diagnostic> diagnostics) {
        if (config == null) {
            return emptyClasspaths();
        }
        if (!Files.exists(lockfilePath)) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    "LOCKFILE_MISSING",
                    "Could not find zolt.lock.",
                    lockfilePath,
                    "Run zolt resolve."));
            return emptyClasspaths();
        }
        try {
            ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
            ClasspathSet dependencyClasspaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
            Path mainOutput = outputPath(root, "[build.output].main", config.build().output(), diagnostics);
            Path testOutput = outputPath(root, "[build.output].test", config.build().testOutput(), diagnostics);
            return new IdeModel.ClasspathInfo(
                    absoluteEntries(dependencyClasspaths.compile()),
                    withOutputs(nonNullPaths(mainOutput), dependencyClasspaths.runtime()),
                    withOutputs(nonNullPaths(mainOutput, testOutput), dependencyClasspaths.test()),
                    absoluteEntries(dependencyClasspaths.processor()),
                    absoluteEntries(dependencyClasspaths.testProcessor()),
                    absoluteEntries(dependencyClasspaths.quarkusDeployment()));
        } catch (LockfileReadException exception) {
            diagnostics.add(new IdeModel.Diagnostic(
                    "error",
                    lockfileReadDiagnosticCode(exception),
                    exception.getMessage(),
                    lockfilePath,
                    "Run zolt resolve."));
            return emptyClasspaths();
        }
    }

    private static String lockfileReadDiagnosticCode(LockfileReadException exception) {
        return exception.getMessage().contains("integrity check failed")
                ? "LOCKFILE_INTEGRITY_FAILED"
                : "LOCKFILE_UNREADABLE";
    }

    private static IdeModel.ClasspathInfo emptyClasspaths() {
        return new IdeModel.ClasspathInfo(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static Path outputPath(
            Path root,
            String key,
            String configuredPath,
            List<IdeModel.Diagnostic> diagnostics) {
        try {
            return ProjectPaths.output(root, key, configuredPath);
        } catch (ProjectPathException exception) {
            pathDiagnostic(root, exception, diagnostics);
            return null;
        }
    }

    private static void pathDiagnostic(
            Path root,
            ProjectPathException exception,
            List<IdeModel.Diagnostic> diagnostics) {
        if (diagnostics.stream().anyMatch(diagnostic ->
                "PROJECT_PATH_INVALID".equals(diagnostic.code())
                        && exception.getMessage().equals(diagnostic.message()))) {
            return;
        }
        diagnostics.add(new IdeModel.Diagnostic(
                "error",
                "PROJECT_PATH_INVALID",
                exception.getMessage(),
                root.resolve("zolt.toml").normalize(),
                "Fix the unsafe path in zolt.toml and run zolt ide model --format json again."));
    }

    private static List<Path> nonNullPaths(Path... paths) {
        List<Path> values = new ArrayList<>();
        for (Path path : paths) {
            if (path != null) {
                values.add(path);
            }
        }
        return List.copyOf(values);
    }

    private static List<Path> withOutputs(List<Path> outputs, Classpath classpath) {
        List<Path> entries = new ArrayList<>(outputs);
        entries.addAll(absoluteEntries(classpath));
        return entries;
    }

    private static List<Path> absoluteEntries(Classpath classpath) {
        return classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }
}
