package sh.zolt.toml;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.DependencySection;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.toml.dependency.DependencySectionCodec;
import sh.zolt.toml.dependency.ProjectConfigDependencyMutator;
import sh.zolt.toml.generated.GeneratedSectionCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
        AtomicManifestWriter.writePrepared(path, original.source(), edited.source());
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
