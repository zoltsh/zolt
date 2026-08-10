package sh.zolt.toml;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.DependencySection;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.toml.dependency.DependencySectionCodec;
import sh.zolt.toml.dependency.ProjectConfigDependencyMutator;
import sh.zolt.toml.generated.GeneratedSectionCodec;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

public final class ZoltTomlWriter {
    public ProjectConfig defaultApplicationConfig(String name, String group, String mainClass) {
        return ProjectConfigs.withDirectDependencies(
                ProjectSectionCodec.defaultApplicationProject(name, group, mainClass),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    public void write(Path path, ProjectConfig config) {
        try {
            Files.writeString(path, write(config));
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not write zolt.toml at " + path + ". Check that the directory exists and is writable.");
        }
    }

    /**
     * Produces a targeted edit of a user-owned manifest. Unchanged source remains byte-for-byte
     * identical, including domains that are intentionally not represented by {@link ProjectConfig}.
     */
    public String patch(ZoltManifestDocument document, ProjectConfig updated) {
        return patchDocument(document, updated).source();
    }

    public ZoltManifestDocument patchDocument(ZoltManifestDocument document, ProjectConfig updated) {
        String patched = ZoltManifestPatcher.patch(document.source(), document.config(), updated, this);
        ZoltTomlParser parser = new ZoltTomlParser();
        ProjectConfig expected = parser.parse(write(updated));
        ProjectConfig reparsed = parser.parse(patched);
        if (!expected.equals(reparsed)) {
            throw new ZoltConfigException(
                    "Could not safely edit zolt.toml because the patched manifest did not match the requested configuration. No changes were written.");
        }
        return new ZoltManifestDocument(patched, reparsed);
    }

    /**
     * Atomically commits a targeted manifest edit after confirming the file is still the exact
     * document the caller parsed.
     */
    public void writePreserving(Path path, ZoltManifestDocument document, ProjectConfig updated) {
        writePrepared(path, document, patchDocument(document, updated));
    }

    public void writePrepared(
            Path path,
            ZoltManifestDocument original,
            ZoltManifestDocument edited) {
        if (edited.source().equals(original.source())) {
            return;
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Path staged = null;
        try {
            requireUnchanged(absolute, original.source());
            staged = Files.createTempFile(parent, ".zolt-manifest-", ".tmp");
            Files.writeString(staged, edited.source());
            copyPermissions(absolute, staged);
            requireUnchanged(absolute, original.source());
            moveAtomically(staged, absolute);
            staged = null;
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not write zolt.toml at " + path + ". Check that the directory exists and is writable.");
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Preserve the edit failure; the uniquely named sibling is safe to clean later.
                }
            }
        }
    }

    private static void requireUnchanged(Path path, String expected) throws IOException {
        String current = Files.readString(path);
        if (!current.equals(expected)) {
            throw new ZoltConfigException(
                    "zolt.toml changed while the edit was in progress. No changes were written; retry the command against the current manifest.");
        }
    }

    private static void copyPermissions(Path source, Path target) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems retain their platform defaults.
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic manifest replacement is not supported at " + target, exception);
        }
    }

    public String write(ProjectConfig config) {
        StringBuilder toml = new StringBuilder();
        ProjectSectionCodec.write(toml, config.project());
        RepositorySectionCodec.writeRepositories(toml, config.repositorySettings());
        RepositorySectionCodec.writeRepositoryCredentials(toml, config.repositoryCredentials());
        VersionAliasSectionCodec.write(toml, config.versionAliases());
        PlatformSectionCodec.write(toml, config.platforms(), config.dependencyMetadata());
        DependencyPolicySectionCodec.write(toml, config.dependencyPolicy());
        DependencySectionCodec.write(toml, config);
        BuildSectionCodec.writeTestSources(toml, config.build());
        BuildSectionCodec.writeTestRuntime(toml, config.build().testRuntime());
        BuildSectionCodec.writeTestSuites(toml, config.build().testSuites());
        BuildSectionCodec.writeBuild(toml, config.build());
        BuildSectionCodec.writeBuildMetadata(toml, config.build().metadata());
        BuildSectionCodec.writeResources(toml, config.build());
        GeneratedSectionCodec.write(toml, config.build());
        CompilerSectionCodec.write(toml, config.compilerSettings(), config.build());
        PackageSectionCodec.write(toml, config.packageSettings());
        FrameworkSectionCodec.write(toml, config.frameworkSettings());
        NativeSectionCodec.write(toml, config.nativeSettings(), config.build());
        return toml.toString();
    }

    public ProjectConfig addDependency(
            ProjectConfig config,
            DependencySection section,
            String coordinate,
            String version) {
        return ProjectConfigDependencyMutator.addDependency(config, section, coordinate, version);
    }

    public ProjectConfig addVersionRefDependency(
            ProjectConfig config,
            DependencySection section,
            String coordinate,
            String versionRef,
            String version) {
        return ProjectConfigDependencyMutator.addVersionRefDependency(
                config,
                section,
                coordinate,
                versionRef,
                version);
    }

    public ProjectConfig addManagedDependency(ProjectConfig config, DependencySection section, String coordinate) {
        return ProjectConfigDependencyMutator.addManagedDependency(config, section, coordinate);
    }

    public ProjectConfig removeDependency(ProjectConfig config, DependencySection section, String coordinate) {
        return ProjectConfigDependencyMutator.removeDependency(config, section, coordinate);
    }

    public ProjectConfig addPlatform(ProjectConfig config, String coordinate, String version) {
        return ProjectConfigDependencyMutator.addPlatform(config, coordinate, version);
    }

    public ProjectConfig addVersionRefPlatform(
            ProjectConfig config,
            String coordinate,
            String versionRef,
            String version) {
        return ProjectConfigDependencyMutator.addVersionRefPlatform(
                config,
                coordinate,
                versionRef,
                version);
    }

    public ProjectConfig removePlatform(ProjectConfig config, String coordinate) {
        return ProjectConfigDependencyMutator.removePlatform(config, coordinate);
    }
}
