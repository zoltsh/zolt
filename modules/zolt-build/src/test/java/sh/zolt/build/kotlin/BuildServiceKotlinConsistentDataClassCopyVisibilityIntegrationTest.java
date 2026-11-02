package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for module-wide data-class copy visibility. */
final class BuildServiceKotlinConsistentDataClassCopyVisibilityIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesCopyVisibilityAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertTrue(copyIsPublic(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult consistent = build(service, true);
        assertFalse(consistent.mainCompilationSkipped());
        assertFalse(copyIsPublic(artifacts.applicationClasspath()));

        BuildResult consistentWarm = build(service, true);
        assertTrue(consistentWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertTrue(copyIsPublic(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean consistentVisibility) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(consistentVisibility),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/Secret.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                data class Secret private constructor(val value: String) {
                    companion object {
                        fun create(value: String): Secret = Secret(value)
                    }
                }
                """);
    }

    private boolean copyIsPublic(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> secret = Class.forName("com.example.Secret", false, loader);
            return Modifier.isPublic(secret.getDeclaredMethod("copy", String.class).getModifiers());
        }
    }

    private static ProjectConfig config(boolean consistentVisibility) {
        String compilerArguments = consistentVisibility
                ? "\"-parameters\", \"-Xconsistent-data-class-copy-visibility\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-data-class-copy-visibility"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = [%s]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(compilerArguments));
    }
}
