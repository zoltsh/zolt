package sh.zolt.build.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.packaging.PackageServiceTestSupport;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpringBootPackageToolingPreparerTest {
    @TempDir
    private Path projectDir;

    @Test
    void skipsNonSpringBootPackageModes() throws IOException {
        RecordingResolver resolver = new RecordingResolver();
        SpringBootPackageToolingPreparer preparer = new SpringBootPackageToolingPreparer(
                resolver,
                new ZoltLockfileReader());
        Files.writeString(projectDir.resolve("zolt.lock"), missingLoaderLockfile());

        preparer.prepareIfNeeded(
                projectDir,
                config(PackageMode.THIN, Map.of(), Map.of(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE.toString(), "3.3.6"), Map.of()),
                projectDir.resolve("cache"));

        assertEquals(0, resolver.calls);
    }

    @Test
    void skipsWhenLockfileDoesNotExist() {
        RecordingResolver resolver = new RecordingResolver();
        SpringBootPackageToolingPreparer preparer = new SpringBootPackageToolingPreparer(
                resolver,
                new ZoltLockfileReader());

        preparer.prepareIfNeeded(
                projectDir,
                config(PackageMode.SPRING_BOOT, Map.of(), Map.of(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE.toString(), "3.3.6"), Map.of()),
                projectDir.resolve("cache"));

        assertEquals(0, resolver.calls);
    }

    @Test
    void resolvesWhenSpringBootRuntimeLoaderIsMissingAndConfigCanProvideIt() throws IOException {
        RecordingResolver resolver = new RecordingResolver();
        SpringBootPackageToolingPreparer preparer = new SpringBootPackageToolingPreparer(
                resolver,
                new ZoltLockfileReader());
        Files.writeString(projectDir.resolve("zolt.lock"), missingLoaderLockfile());
        Path cacheRoot = projectDir.resolve("cache");
        ProjectConfig config = config(
                PackageMode.SPRING_BOOT,
                Map.of("spring-boot", "org.springframework.boot:spring-boot-dependencies:3.3.6"),
                Map.of(),
                Map.of());

        preparer.prepareIfNeeded(projectDir, config, cacheRoot);

        assertEquals(1, resolver.calls);
        assertEquals(projectDir.toAbsolutePath().normalize(), resolver.projectRoot);
        assertEquals(config, resolver.config);
        assertEquals(cacheRoot, resolver.cacheRoot);
    }

    @Test
    void skipsWhenRuntimeLoaderIsAlreadyLocked() {
        SpringBootPackageToolingPreparer preparer = new SpringBootPackageToolingPreparer(
                new RecordingResolver(),
                new ZoltLockfileReader());

        assertFalse(preparer.shouldResolveTooling(
                lockfile(runtimeLoaderPackage()),
                config(PackageMode.SPRING_BOOT, Map.of(), Map.of(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE.toString(), "3.3.6"), Map.of())));
    }

    @Test
    void providedLoaderDoesNotSatisfyRuntimeTooling() {
        SpringBootPackageToolingPreparer preparer = new SpringBootPackageToolingPreparer(
                new RecordingResolver(),
                new ZoltLockfileReader());

        assertTrue(preparer.shouldResolveTooling(
                lockfile(providedLoaderPackage()),
                config(PackageMode.SPRING_BOOT, Map.of(), Map.of(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE.toString(), "3.3.6"), Map.of())));
    }

    @Test
    void classifiedRuntimeLoaderDoesNotSatisfyDefaultTooling() {
        SpringBootPackageToolingPreparer preparer =
                new SpringBootPackageToolingPreparer(
                        new RecordingResolver(),
                        new ZoltLockfileReader());

        assertTrue(preparer.shouldResolveTooling(
                lockfile(classifiedRuntimeLoaderPackage()),
                config(
                        PackageMode.SPRING_BOOT,
                        Map.of(
                                "spring-boot",
                                "org.springframework.boot:spring-boot-dependencies:3.3.6"),
                        Map.of(),
                        Map.of())));
    }

    @Test
    void skipsWhenConfigCannotResolveLoaderVersion() {
        SpringBootPackageToolingPreparer preparer = new SpringBootPackageToolingPreparer(
                new RecordingResolver(),
                new ZoltLockfileReader());

        assertFalse(preparer.shouldResolveTooling(
                lockfile(springBootPackage()),
                config(PackageMode.SPRING_BOOT, Map.of(), Map.of(), Map.of())));
    }

    @Test
    void apiLoaderDependencyCanProvideToolingVersion() {
        SpringBootPackageToolingPreparer preparer = new SpringBootPackageToolingPreparer(
                new RecordingResolver(),
                new ZoltLockfileReader());

        assertTrue(preparer.shouldResolveTooling(
                lockfile(springBootPackage()),
                config(PackageMode.SPRING_BOOT, Map.of(), Map.of(), Map.of(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE.toString(), "3.3.6"))));
    }

    private static ProjectConfig config(
            PackageMode mode,
            Map<String, String> platforms,
            Map<String, String> dependencies,
            Map<String, String> apiDependencies) {
        return ProjectConfigs.withAllDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", PackageServiceTestSupport.currentJavaMajorVersion(), Optional.empty()),
                Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                platforms,
                apiDependencies,
                Set.of(),
                Map.of(),
                dependencies,
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                NativeSettings.defaults(),
                CompilerSettings.defaults(),
                new PackageSettings(mode));
    }

    private static ZoltLockfile lockfile(String packageContent) {
        return new ZoltLockfileReader().read("version = 1\n\n" + packageContent);
    }

    private static String missingLoaderLockfile() {
        return "version = 1\n\n" + springBootPackage();
    }

    private static String springBootPackage() {
        return """
                [[package]]
                id = "org.springframework.boot:spring-boot"
                version = "3.3.6"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """;
    }

    private static String runtimeLoaderPackage() {
        return """
                [[package]]
                id = "org.springframework.boot:spring-boot-loader"
                version = "3.3.6"
                source = "maven-central"
                scope = "runtime"
                direct = false
                dependencies = []
                """;
    }

    private static String providedLoaderPackage() {
        return """
                [[package]]
                id = "org.springframework.boot:spring-boot-loader"
                version = "3.3.6"
                source = "maven-central"
                scope = "provided"
                direct = false
                dependencies = []
                """;
    }

    private static String classifiedRuntimeLoaderPackage() {
        return """
                [[package]]
                id = "org.springframework.boot:spring-boot-loader"
                version = "3.3.6"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "org/springframework/boot/spring-boot-loader/3.3.6/spring-boot-loader-3.3.6-tests.jar"
                dependencies = []
                """;
    }

    private static final class RecordingResolver implements SpringBootPackageToolingPreparer.Resolver {
        private int calls;
        private Path projectRoot;
        private ProjectConfig config;
        private Path cacheRoot;

        @Override
        public void resolve(Path projectRoot, ProjectConfig config, Path cacheRoot) {
            calls++;
            this.projectRoot = projectRoot;
            this.config = config;
            this.cacheRoot = cacheRoot;
        }
    }
}
