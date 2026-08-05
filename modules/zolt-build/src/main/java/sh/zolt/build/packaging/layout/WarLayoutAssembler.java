package sh.zolt.build.packaging.layout;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.GeneratedManifest;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageRuntimeJar;
import sh.zolt.build.packaging.PackageRuntimeJars;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageplan.PackageInputBudget;
import sh.zolt.build.packageplan.PackageInputEntry;
import sh.zolt.build.packageplan.PackageInputSnapshot;
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
        return assemble(
                projectDirectory,
                config,
                buildResult,
                outputDirectory,
                warPath,
                runtimeJars,
                PackageInputSnapshot.of(outputDirectory, PackageInputBudget.defaults()),
                new PackageArchiveDigests());
    }

    public PackageResult assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Path outputDirectory,
            Path warPath,
            List<PackageRuntimeJar> runtimeJars,
            PackageInputSnapshot inputs,
            PackageArchiveDigests digests) {
        GeneratedManifest manifest = manifestGenerator.generateWithoutMain(projectDirectory, config);
        PackageRuntimeJars.requireUniqueNestedPaths(WEB_INF_LIB_PREFIX, runtimeJars);

        try {
            Files.createDirectories(warPath.getParent());
            List<PackageInputEntry> files = inputs.entries();
            try (PackageArchiveWriter archive = PackageArchiveWriter.open(warPath)) {
                archive.writeEntry(manifest.path(), manifest.content());
                archive.writeDirectory(WEB_INF_PREFIX);
                archive.writeDirectory(WEB_INF_CLASSES_PREFIX);
                archive.writeDirectory(WEB_INF_LIB_PREFIX);
                for (PackageInputEntry file : files) {
                    String warEntryName = WEB_INF_CLASSES_PREFIX + file.name();
                    archive.writeParentDirectories(warEntryName);
                    archive.writeEntry(warEntryName, output -> inputs.transferTo(file, output));
                }
                for (PackageRuntimeJar runtimeJar : runtimeJars) {
                    archive.writeStoredEntry(
                            WEB_INF_LIB_PREFIX + PackageRuntimeJars.nestedJarName(runtimeJar),
                            PackageRuntimeJars.read(runtimeJar));
                }
                archive.commit();
                archive.archiveSha256().ifPresent(sha256 -> digests.record(warPath, sha256));
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

}
