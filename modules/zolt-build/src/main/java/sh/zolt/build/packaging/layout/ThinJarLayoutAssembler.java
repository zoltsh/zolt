package sh.zolt.build.packaging.layout;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.GeneratedManifest;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageplan.PackageInputBudget;
import sh.zolt.build.packageplan.PackageInputEntry;
import sh.zolt.build.packageplan.PackageInputSnapshot;
import sh.zolt.classpath.ResolvedClasspathPackage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class ThinJarLayoutAssembler {
    private final ManifestGenerator manifestGenerator;
    private final ZoltLockfileReader lockfileReader;
    private final ClasspathBuilder classpathBuilder;

    public ThinJarLayoutAssembler(
            ManifestGenerator manifestGenerator,
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder) {
        this.manifestGenerator = manifestGenerator;
        this.lockfileReader = lockfileReader;
        this.classpathBuilder = classpathBuilder;
    }

    public PackageResult assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Path jarPath,
            Optional<Path> cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths) {
        return assemble(
                projectDirectory,
                config,
                buildResult,
                jarPath,
                cacheRoot,
                classpathPackages,
                classpaths,
                PackageInputSnapshot.of(
                        buildResult.outputDirectory(),
                        PackageInputBudget.defaults()),
                new PackageArchiveDigests());
    }

    public PackageResult assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Path jarPath,
            Optional<Path> cacheRoot,
            Optional<List<ResolvedClasspathPackage>> classpathPackages,
            Optional<ClasspathSet> classpaths,
            PackageInputSnapshot inputs,
            PackageArchiveDigests digests) {
        requireOutputDirectory(buildResult);
        Path runtimeClasspathPath = runtimeClasspathPath(jarPath);
        GeneratedManifest manifest = manifestGenerator.generate(projectDirectory, config);

        try {
            Files.createDirectories(jarPath.getParent());
            List<PackageInputEntry> files = inputs.entries();
            try (PackageArchiveWriter archive = PackageArchiveWriter.open(jarPath)) {
                archive.writeEntry(manifest.path(), manifest.content());
                for (PackageInputEntry file : files) {
                    archive.writeEntry(file.name(), output -> inputs.transferTo(file, output));
                }
                archive.commit();
                archive.archiveSha256().ifPresent(sha256 -> digests.record(jarPath, sha256));
            }
            Optional<Path> writtenRuntimeClasspathPath = Optional.empty();
            if (classpathPackages.isPresent()) {
                writeRuntimeClasspath(runtimeClasspathPath, classpathPackages.orElseThrow());
                writtenRuntimeClasspathPath = Optional.of(runtimeClasspathPath);
            } else if (classpaths.isPresent()) {
                writeRuntimeClasspath(runtimeClasspathPath, classpaths.orElseThrow());
                writtenRuntimeClasspathPath = Optional.of(runtimeClasspathPath);
            } else if (cacheRoot.isPresent()) {
                writeRuntimeClasspath(projectDirectory, cacheRoot.orElseThrow(), runtimeClasspathPath);
                writtenRuntimeClasspathPath = Optional.of(runtimeClasspathPath);
            }
            return new PackageResult(
                    buildResult,
                    PackageMode.THIN,
                    jarPath,
                    writtenRuntimeClasspathPath,
                    files.size(),
                    manifest.mainClass().isPresent());
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not package jar at "
                            + jarPath
                            + ". Check that target/ is writable and try again.",
                    exception);
        }
    }

    private void requireOutputDirectory(BuildResult buildResult) {
        Path outputDirectory = buildResult.outputDirectory();
        if (!Files.isDirectory(outputDirectory)) {
            throw new PackageException(
                    "Build output directory does not exist at "
                            + outputDirectory
                            + ". Run zolt build and check [build.output].main in zolt.toml.");
        }
    }

    private void writeRuntimeClasspath(
            Path projectDirectory,
            Path cacheRoot,
            Path runtimeClasspathPath) throws IOException {
        ZoltLockfile lockfile = lockfileReader.read(projectDirectory.resolve("zolt.lock"));
        writeRuntimeClasspath(runtimeClasspathPath, packagedClasspathPackages(lockfile, cacheRoot));
    }

    private void writeRuntimeClasspath(
            Path runtimeClasspathPath,
            List<ResolvedClasspathPackage> classpathPackages) throws IOException {
        ClasspathSet classpaths = classpathBuilder.build(packagedClasspathPackages(classpathPackages));
        writeRuntimeClasspath(runtimeClasspathPath, classpaths);
    }

    private void writeRuntimeClasspath(
            Path runtimeClasspathPath,
            ClasspathSet classpaths) throws IOException {
        String content = classpaths.runtime().entries().stream()
                .map(Path::toString)
                .collect(Collectors.joining("\n"));
        if (!content.isEmpty()) {
            content = content + "\n";
        }
        PackageArchiveWriter.writeStringAtomically(
                runtimeClasspathPath,
                content,
                StandardCharsets.UTF_8);
    }

    private List<ResolvedClasspathPackage> packagedClasspathPackages(ZoltLockfile lockfile, Path cacheRoot) {
        return packagedClasspathPackages(LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot));
    }

    private static List<ResolvedClasspathPackage> packagedClasspathPackages(
            List<ResolvedClasspathPackage> classpathPackages) {
        return classpathPackages.stream()
                .filter(dependency -> dependency.scope().packagedByDefault())
                .toList();
    }

    private static Path runtimeClasspathPath(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        if (fileName.endsWith(".jar")) {
            return jarPath.resolveSibling(fileName.substring(0, fileName.length() - 4) + ".runtime-classpath");
        }
        return jarPath.resolveSibling(fileName + ".runtime-classpath");
    }

}
