package sh.zolt.build.packaging;

import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.BuildService;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.ResourceTokenSettings;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.dependency.PackageId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Assumptions;

public final class PackageServiceTestSupport {
    private PackageServiceTestSupport() {
    }

    public static ProjectConfig config(Optional<String> mainClass) {
        return config(new ProjectMetadata("demo", "0.1.0", "com.example", currentJavaMajorVersion(), mainClass));
    }

    public static ProjectConfig config(ProjectMetadata projectMetadata) {
        return ProjectConfigs.withDirectDependencies(
                projectMetadata,
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    public static BuildSettings buildSettingsWithMetadata(BuildMetadataSettings metadataSettings) {
        return new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                metadataSettings);
    }

    public static ResourceFilteringSettings resourceFilteringSettings() {
        return new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of(
                        "projectName", ResourceTokenSettings.project("name"),
                        "projectVersion", ResourceTokenSettings.project("version"),
                        "serverPort", ResourceTokenSettings.literal("0")));
    }

    public static void source(Path projectDir, String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    public static void writeLockfile(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
    }

    public static void recordCurrentTestCompile(
            Path projectDir,
            ProjectConfig config,
            Path cacheRoot) {
        BuildResultWithClasspaths build =
                new BuildService().buildWithClasspaths(
                        projectDir,
                        config,
                        cacheRoot,
                        false);
        SourceDiscoveryResult sources = new SourceDiscoverer()
                .discover(projectDir, config.build());
        List<Path> testCompileEntries = new java.util.ArrayList<>();
        testCompileEntries.add(
                build.buildResult().outputDirectory());
        testCompileEntries.addAll(
                build.classpaths().testCompile().entries());
        new BuildFingerprintService().writeTestCompileFingerprint(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                new GeneratedSourceProducerFingerprintService()
                        .fingerprintsTest(
                                projectDir,
                                config,
                                build.classpathPackages()),
                new Classpath(testCompileEntries),
                build.classpaths().testProcessor(),
                projectDir.resolve(config.build().testOutput()),
                projectDir.resolve(
                        config.compilerSettings()
                                .generatedTestSources()));
    }

    public static void createJarWithEntry(Path jarPath, String entryName) throws IOException {
        createJarWithEntries(jarPath, Map.of(entryName, "\0"));
    }

    public static void createJarWithEntries(Path jarPath, Map<String, String> entries) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, String> entry : entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    public static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }

    public static String readEntry(JarFile jar, String name) throws IOException {
        try (InputStream input = jar.getInputStream(jar.getEntry(name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static String nestedJarName(String groupId, String artifactId, String version) {
        return NestedArtifactIdentity.external(
                        new PackageId(groupId, artifactId),
                        version)
                .nestedJarName();
    }

    public static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
