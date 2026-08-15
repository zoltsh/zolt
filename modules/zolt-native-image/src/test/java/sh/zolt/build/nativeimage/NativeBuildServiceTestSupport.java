package sh.zolt.build.nativeimage;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.io.TempDir;

abstract class NativeBuildServiceTestSupport {
    @TempDir
    protected Path projectDir;

    protected NativeBuildService service(NativeImageRunner.ProcessRunner processRunner) {
        return new NativeBuildService(
                new PackageService(),
                new ZoltLockfileReader(),
                new ClasspathBuilder(),
                new NativeImageRunner(":", processRunner));
    }

    protected NativeBuildService serviceLauncher(NativeImageRunner.ProcessLauncher processLauncher) {
        return new NativeBuildService(
                new PackageService(),
                new ZoltLockfileReader(),
                new ClasspathBuilder(),
                new NativeImageRunner(":", processLauncher));
    }

    protected void writeRuntimeLockfile() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path jar = writeRuntimeJar(cacheRoot);
        String digest = sha256(jar);
        String relativeJar = cacheRoot.relativize(jar).toString().replace('\\', '/');
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "%s"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(relativeJar, digest));
    }

    protected static Path writeRuntimeJar(Path cacheRoot) throws IOException {
        Path staging = cacheRoot.resolve("runtime-lib.jar.staging");
        Files.createDirectories(staging.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(staging))) {
            output.putNextEntry(new JarEntry("com/example/runtime/RuntimeLib.class"));
            output.write("runtime".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        String digest = sha256(staging);
        Path jar = cacheRoot.resolve("blobs/v2/sha256/" + digest + "/runtime-lib-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.move(staging, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return jar;
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    protected void source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    protected static void writeNativeBinary(Path outputBinary) {
        try {
            Files.writeString(outputBinary, "native");
            outputBinary.toFile().setExecutable(true, false);
        } catch (IOException exception) {
            throw new AssertionError("Could not write fake native binary", exception);
        }
    }

    protected static ProjectConfig config(Optional<String> mainClass) {
        return config(
                mainClass,
                new NativeSettings(
                        "demo-native",
                        "target/native-custom",
                        List.of("--no-fallback", "--native-image-info")));
    }

    protected static ProjectConfig config(Optional<String> mainClass, NativeSettings nativeSettings) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", currentJavaMajorVersion(), mainClass),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults(),
                nativeSettings);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
