package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class BuildServiceGroovyToolchainPreflightTest {
    @TempDir
    private Path projectDir;

    @Test
    void legacyClasspathBuildFailsActionablyBeforeDeletingOutput() throws IOException {
        Path staleClass = prepareGroovyProjectWithStaleOutput();

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service().build(projectDir, config(), emptyClasspaths()));

        assertNotNull(exception.actionableError());
        assertTrue(exception.actionableError().summary().contains("verified resolved package metadata"));
        assertTrue(exception.actionableError().summary().contains("legacy"));
        assertTrue(exception.actionableError().remediation().contains("cache-root build overload"));
        assertTrue(Files.exists(staleClass));
    }

    @Test
    void missingVerifiedGroovyPackageFailsBeforeDeletingOutput() throws IOException {
        Path staleClass = prepareGroovyProjectWithStaleOutput();

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> service().build(
                        ProjectBuildContext.standalone(projectDir),
                        config(),
                        emptyClasspaths(),
                        List.of()));

        assertTrue(exception.getMessage().contains("not present in the verified resolved packages"));
        assertTrue(Files.exists(staleClass));
    }

    @Test
    void invalidGroovyPackageFailsBeforeDeletingOutput() throws IOException {
        Path staleClass = prepareGroovyProjectWithStaleOutput();
        PackageId groovy = new PackageId("org.apache.groovy", "groovy");
        ResolvedClasspathPackage invalidPackage = new ResolvedClasspathPackage(
                new ResolvedPackage(
                        groovy,
                        "4.0.22",
                        true,
                        Path.of(""),
                        projectDir.resolve("missing/groovy-4.0.22.jar")),
                DependencyScope.COMPILE);

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> service().build(
                        ProjectBuildContext.standalone(projectDir),
                        config(),
                        emptyClasspaths(),
                        List.of(invalidPackage)));

        assertTrue(exception.getMessage().contains("not a regular file"));
        assertTrue(Files.exists(staleClass));
    }

    private BuildService service() {
        return new BuildService(requiredVersion -> new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of(currentJavaMajorVersion() + ".0.1"),
                Optional.of("verified-jdk"),
                requiredVersion));
    }

    private Path prepareGroovyProjectWithStaleOutput() throws IOException {
        Path source = projectDir.resolve("src/main/java/com/example/Main.groovy");
        Path staleClass = projectDir.resolve("target/classes/com/example/StillHere.class");
        Files.createDirectories(source.getParent());
        Files.createDirectories(staleClass.getParent());
        Files.writeString(source, "package com.example\nclass Main {}\n");
        Files.write(staleClass, new byte[] {1});
        return staleClass;
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        currentJavaMajorVersion(),
                        Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of("org.apache.groovy:groovy", "4.0.22"),
                Map.of(),
                BuildSettings.defaults());
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty);
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
