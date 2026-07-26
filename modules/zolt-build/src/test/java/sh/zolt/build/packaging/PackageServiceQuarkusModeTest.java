package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.createJarWithEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static sh.zolt.build.packaging.PackageServiceTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.BuildService;
import sh.zolt.build.PackageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.framework.FrameworkPackagePlanDependency;
import sh.zolt.framework.FrameworkPackagePlanRules;
import sh.zolt.framework.FrameworkPackageResult;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.QuarkusPackageMode;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.resolve.ResolveService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceQuarkusModeTest {
    @TempDir
    private Path projectDir;

    @Test
    void quarkusPackageModeReturnsAugmentedRunnerJar() throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        Path packageDirectory = projectDir.resolve("target/quarkus-app");
        Path runnerJar = packageDirectory.resolve("quarkus-run.jar");
        createJarWithEntry(runnerJar, "io/quarkus/bootstrap/runner/QuarkusEntryPoint.class");
        Files.createDirectories(packageDirectory.resolve("app"));
        Files.writeString(packageDirectory.resolve("app/app.jar"), "app\n");
        boolean[] augmented = new boolean[1];
        PackageService service = new PackageService(
                new BuildService(),
                new ResolveService(),
                new ManifestGenerator(),
                new ZoltLockfileReader(),
                new ClasspathBuilder(),
                (projectDirectory, config, cacheRoot) -> {
                    augmented[0] = true;
                    return Optional.of(new FrameworkPackageResult(
                            PackageMode.QUARKUS,
                            packageDirectory,
                            runnerJar,
                            "target/quarkus-app/app"));
                },
                new PackagePlanService(List.of(
                        new TestQuarkusPackagePlanRules(runnerJar))));
        ProjectConfig config = config(Optional.empty())
                .withPackageSettings(new PackageSettings(PackageMode.QUARKUS))
                .withFrameworkSettings(new FrameworkSettings(new QuarkusSettings(true, QuarkusPackageMode.FAST_JAR)));

        PackageResult result = service.packageJar(projectDir, config, projectDir.resolve("cache"));

        assertEquals(PackageMode.QUARKUS, result.mode());
        assertEquals(runnerJar, result.jarPath());
        assertEquals("target/quarkus-app/app", result.applicationLayout());
        assertEquals(2, result.entryCount());
        assertTrue(result.hasMainClass());
        assertTrue(augmented[0]);
    }

    private record TestQuarkusPackagePlanRules(Path runnerJar)
            implements FrameworkPackagePlanRules {
        @Override
        public boolean supports(PackageMode mode) {
            return mode == PackageMode.QUARKUS;
        }

        @Override
        public FrameworkPackagePlanDependency dependency(
                LockPackage lockPackage,
                ProjectConfig config) {
            throw new UnsupportedOperationException(
                    "fixture has no dependencies");
        }

        @Override
        public Path archivePath(
                Path projectRoot,
                ProjectConfig config) {
            return runnerJar;
        }

        @Override
        public String applicationLayout(ProjectConfig config) {
            return "target/quarkus-app/app";
        }
    }

    @Test
    void quarkusPackageModeRequiresEnabledFramework() throws IOException {
        writeLockfile(projectDir);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        PackageService service = new PackageService(
                new BuildService(),
                new ResolveService(),
                new ManifestGenerator(),
                new ZoltLockfileReader(),
                new ClasspathBuilder(),
                (projectDirectory, config, cacheRoot) -> Optional.empty());
        ProjectConfig config = config(Optional.empty())
                .withPackageSettings(new PackageSettings(PackageMode.QUARKUS));

        PackageException exception = assertThrows(
                PackageException.class,
                () -> service.packageJar(projectDir, config, projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("Framework package mode `quarkus` requires a matching framework adapter"));
        assertTrue(exception.getMessage().contains("Enable the framework in zolt.toml"));
    }
}
