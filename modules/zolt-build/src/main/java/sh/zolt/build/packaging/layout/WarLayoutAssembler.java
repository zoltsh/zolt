package sh.zolt.build.packaging.layout;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.GeneratedManifest;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageRuntimeJar;
import sh.zolt.build.packaging.PackageRuntimeJars;
import sh.zolt.build.packageplan.PackageInputFingerprinting;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class WarLayoutAssembler {
    private static final String WEB_INF_PREFIX = "WEB-INF/";
    private static final String WEB_INF_CLASSES_PREFIX = "WEB-INF/classes/";
    private static final String WEB_INF_LIB_PREFIX = "WEB-INF/lib/";
    private final ManifestGenerator manifestGenerator;

    public WarLayoutAssembler(ManifestGenerator manifestGenerator) {
        this.manifestGenerator = manifestGenerator;
    }

    public PackageResult assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Path outputDirectory,
            Path warPath,
            List<PackageRuntimeJar> runtimeJars) {
        GeneratedManifest manifest = manifestGenerator.generateWithoutMain(projectDirectory, config);
        PackageRuntimeJars.requireUniqueNestedPaths(WEB_INF_LIB_PREFIX, runtimeJars);

        try {
            Files.createDirectories(warPath.getParent());
            List<Path> files = compiledFiles(outputDirectory);
            try (PackageArchiveWriter archive = PackageArchiveWriter.open(warPath)) {
                archive.writeEntry(manifest.path(), manifest.content());
                archive.writeDirectory(WEB_INF_PREFIX);
                archive.writeDirectory(WEB_INF_CLASSES_PREFIX);
                archive.writeDirectory(WEB_INF_LIB_PREFIX);
                for (Path file : files) {
                    String warEntryName = WEB_INF_CLASSES_PREFIX + entryName(outputDirectory, file);
                    archive.writeParentDirectories(warEntryName);
                    archive.writeFile(warEntryName, file);
                }
                for (PackageRuntimeJar runtimeJar : runtimeJars) {
                    archive.writeStoredEntry(
                            WEB_INF_LIB_PREFIX + PackageRuntimeJars.nestedJarName(runtimeJar),
                            PackageRuntimeJars.read(runtimeJar));
                }
                archive.commit();
            }
            return new PackageResult(
                    buildResult,
                    PackageMode.WAR,
                    warPath,
                    Optional.empty(),
                    files.size(),
                    false);
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not package WAR at "
                            + warPath
                            + ". Check that target/ is writable and try again.",
                    exception);
        }
    }

    private static List<Path> compiledFiles(Path outputDirectory) throws IOException {
        return PackageInputFingerprinting.applicationFiles(outputDirectory);
    }

    private static String entryName(Path outputDirectory, Path file) {
        return outputDirectory.relativize(file).normalize().toString().replace('\\', '/');
    }

}
