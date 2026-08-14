package sh.zolt.build.run;

import sh.zolt.build.BuildService;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.io.TempDir;

abstract class RunPackageServiceTestSupport {
    @TempDir
    protected Path projectDir;

    protected RunPackageService service(JavaRunner.ProcessRunner processRunner) {
        return service(processRunner, new JdkDetector());
    }

    protected RunPackageService service(JavaRunner.ProcessRunner processRunner, JdkChecker jdkChecker) {
        return new RunPackageService(
                new PackageService(),
                new BuildService(jdkChecker),
                new ClasspathBuilder(),
                jdkChecker,
                new JavaRunner(":", processRunner));
    }

    protected void writeRuntimeLockfile() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        emptyJar(cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"));
        emptyJar(cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar"));
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 1

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
    }

    private static void emptyJar(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return;
        }
        Files.createDirectories(path.getParent());
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(path))) {
            // Valid empty jar used only as a classpath lane marker.
        }
    }

    protected void source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    protected static ProjectConfig config(Optional<String> mainClass) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", currentJavaMajorVersion(), mainClass),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    protected static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
