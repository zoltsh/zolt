package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Kotlin annotation declarations targeting older Java runtimes. */
final class BuildServiceKotlinJavaAnnotationTargetsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void removesOnlyNewerJavaTargetsAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals(new JavaTargets(true, true, true), targets(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult compatible = build(service, true);
        assertFalse(compatible.mainCompilationSkipped());
        assertEquals(new JavaTargets(true, false, false), targets(artifacts.applicationClasspath()));

        BuildResult compatibleWarm = build(service, true);
        assertTrue(compatibleWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(new JavaTargets(true, true, true), targets(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean useLegacyTargets) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(useLegacyTargets),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/Compatible.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Target(
                    AnnotationTarget.CLASS,
                    AnnotationTarget.TYPE,
                    AnnotationTarget.TYPE_PARAMETER,
                )
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Compatible
                """);
    }

    private JavaTargets targets(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Target target = Class.forName("com.example.Compatible", false, loader)
                    .getAnnotation(Target.class);
            Set<ElementType> values = Set.copyOf(Arrays.asList(target.value()));
            return new JavaTargets(
                    values.contains(ElementType.TYPE),
                    values.contains(ElementType.TYPE_USE),
                    values.contains(ElementType.TYPE_PARAMETER));
        }
    }

    private static ProjectConfig config(boolean useLegacyTargets) {
        String compilerArguments = useLegacyTargets
                ? "\"-parameters\", \"-Xno-new-java-annotation-targets\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-java-annotation-targets"
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

    private record JavaTargets(boolean declarationType, boolean typeUse, boolean typeParameter) {
    }
}
