package sh.zolt.build.packaging;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.PackageException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.packageplan.PackagePlanOutputs;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PackageSupplementalArtifactAssembler {
    private final ClasspathBuilder classpathBuilder;

    public PackageSupplementalArtifactAssembler(ClasspathBuilder classpathBuilder) {
        this.classpathBuilder = classpathBuilder;
    }

    public List<PackageArtifact> assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths) {
        List<PackageArtifact> artifacts = new ArrayList<>();
        if (config.packageSettings().sources()) {
            artifacts.add(packageSourcesJar(projectDirectory, config));
        }
        if (config.packageSettings().javadoc()) {
            artifacts.add(packageJavadocJar(projectDirectory, config, buildResult, classpathPackages, classpaths));
        }
        if (config.packageSettings().tests()) {
            artifacts.add(packageTestJar(projectDirectory, config));
        }
        return List.copyOf(artifacts);
    }

    private static PackageArtifact packageSourcesJar(Path projectDirectory, ProjectConfig config) {
        List<MainSourceRoot> sourceRoots = mainSourceRoots(projectDirectory, config);
        Path jarPath = PackagePlanOutputs.classifierJarPath(
                projectDirectory,
                config,
                "sources");
        try {
            Files.createDirectories(jarPath.getParent());
            List<SourceJarEntry> entries = sourceJarEntries(sourceRoots);
            try (PackageArchiveWriter archive = PackageArchiveWriter.open(jarPath)) {
                for (SourceJarEntry entry : entries) {
                    archive.writeFile(entry.name(), entry.file());
                }
                archive.commit();
            }
            return new PackageArtifact("sources", jarPath, entries.size());
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not package sources jar at "
                            + jarPath
                            + ". Check that source files are readable and target/ is writable.",
                    exception);
        }
    }

    private PackageArtifact packageJavadocJar(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths) {
        List<MainSourceRoot> sourceRoots = mainSourceRoots(projectDirectory, config);
        Path javadocDirectory = ProjectPaths.output(
                projectDirectory,
                "package javadoc output",
                config.build().outputRoot() + "/javadoc");
        Path jarPath = PackagePlanOutputs.classifierJarPath(
                projectDirectory,
                config,
                "javadoc");
        try {
            Files.createDirectories(jarPath.getParent());
            PackageSupplementalArtifactFiles.deleteDirectory(javadocDirectory);
            Files.createDirectories(javadocDirectory);
            List<Path> sources = sourceFiles(sourceRoots);
            if (!sources.isEmpty()) {
                runJavadoc(
                        projectDirectory,
                        sourceRoots.stream().map(MainSourceRoot::path).toList(),
                        javadocDirectory,
                        sources,
                        javadocClasspath(buildResult, classpathPackages, classpaths));
            }
            List<Path> files = PackageSupplementalArtifactFiles.regularFiles(javadocDirectory);
            PackageArchiveWriter.writeJarFromFiles(jarPath, javadocDirectory, files);
            return new PackageArtifact("javadoc", jarPath, files.size());
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not package javadoc jar at "
                            + jarPath
                            + ". Check that target/ is writable and retry.",
                    exception);
        }
    }

    private static PackageArtifact packageTestJar(Path projectDirectory, ProjectConfig config) {
        Path testOutput = ProjectPaths.output(projectDirectory, "[build.output].test", config.build().testOutput());
        Path jarPath = PackagePlanOutputs.classifierJarPath(
                projectDirectory,
                config,
                "tests");
        if (!Files.isDirectory(testOutput)) {
            throw new PackageException(
                    "Cannot package test jar because compiled test output is missing at "
                            + testOutput
                            + ". Run `zolt test` first, then retry `zolt package`.");
        }
        try {
            Files.createDirectories(jarPath.getParent());
            List<Path> files = PackageSupplementalArtifactFiles.compiledFiles(testOutput);
            PackageArchiveWriter.writeJarFromFiles(jarPath, testOutput, files);
            return new PackageArtifact("tests", jarPath, files.size());
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not package test jar at "
                            + jarPath
                            + ". Check that compiled test output is readable and target/ is writable.",
                    exception);
        }
    }

    private List<Path> javadocClasspath(
            BuildResult buildResult,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths) {
        List<Path> entries = new ArrayList<>();
        entries.add(buildResult.outputDirectory());
        if (classpaths.isPresent()) {
            entries.addAll(classpaths.orElseThrow().compile().entries());
        } else {
            classpathPackages
                    .map(classpathBuilder::build)
                    .ifPresent(resolvedClasspaths -> entries.addAll(resolvedClasspaths.compile().entries()));
        }
        return entries.stream().map(Path::toAbsolutePath).map(Path::normalize).toList();
    }

    private static void runJavadoc(
            Path projectDirectory,
            List<Path> sourceRoots,
            Path outputDirectory,
            List<Path> sources,
            List<Path> classpath) {
        List<String> command = new ArrayList<>();
        command.add(javadocExecutable().toString());
        command.add("-quiet");
        command.add("-d");
        command.add(outputDirectory.toString());
        command.add("-sourcepath");
        command.add(sourceRoots.stream()
                .map(Path::toString)
                .collect(Collectors.joining(java.io.File.pathSeparator)));
        if (!classpath.isEmpty()) {
            command.add("-classpath");
            command.add(classpath.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(java.io.File.pathSeparator)));
        }
        sources.stream().map(Path::toString).forEach(command::add);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(projectDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new PackageException(
                            "javadoc failed with exit code "
                                    + exitCode
                                    + ". Fix Javadoc errors or disable [package].javadoc, then retry.\n"
                                    + output.stripTrailing());
                }
            }
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not run javadoc. Check that the configured JDK includes the javadoc tool.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PackageException("javadoc was interrupted. Try packaging again.", exception);
        }
    }

    private static Path javadocExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", executable("javadoc"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    private static List<MainSourceRoot> mainSourceRoots(Path projectDirectory, ProjectConfig config) {
        List<MainSourceRoot> roots = new ArrayList<>();
        for (String configuredRoot : config.build().sourceRoots()) {
            roots.add(new MainSourceRoot(
                    configuredRoot,
                    ProjectPaths.existingRoot(projectDirectory, "[build].sources", configuredRoot)));
        }
        return List.copyOf(roots);
    }

    private static List<SourceJarEntry> sourceJarEntries(List<MainSourceRoot> sourceRoots) throws IOException {
        List<SourceJarEntry> entries = new ArrayList<>();
        for (MainSourceRoot root : sourceRoots) {
            for (Path file : PackageSupplementalArtifactFiles.sourceFiles(root.path())) {
                entries.add(new SourceJarEntry(entryName(root.path(), file), file));
            }
        }
        return entries.stream()
                .sorted(Comparator.comparing(SourceJarEntry::name)
                        .thenComparing(entry -> entry.file().toString()))
                .toList();
    }

    private static List<Path> sourceFiles(List<MainSourceRoot> sourceRoots) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (MainSourceRoot root : sourceRoots) {
            sources.addAll(PackageSupplementalArtifactFiles.sourceFiles(root.path()));
        }
        return sources.stream()
                .sorted()
                .toList();
    }

    private static String entryName(Path root, Path file) {
        return root.relativize(file).normalize().toString().replace('\\', '/');
    }

    private record MainSourceRoot(String configuredPath, Path path) {
    }

    private record SourceJarEntry(String name, Path file) {
    }
}
