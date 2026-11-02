package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Kotlin 2.2 context parameters. */
final class BuildServiceKotlinContextParametersIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void compilesContextParametersOnlyWhenPreviewIsEnabled() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source(false);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("context-1", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        source(true);
        KotlinCompileException disabled = assertThrows(
                KotlinCompileException.class,
                () -> build(service, false));
        assertTrue(diagnostics(disabled).contains("-xcontext-parameters"), disabled.getMessage());

        BuildResult preview = build(service, true);
        assertFalse(preview.mainCompilationSkipped());
        assertEquals("context-1", invoke(artifacts.applicationClasspath()));

        BuildResult previewWarm = build(service, true);
        assertTrue(previewWarm.mainCompilationSkipped());

        KotlinCompileException removed = assertThrows(
                KotlinCompileException.class,
                () -> build(service, false));
        assertTrue(diagnostics(removed).contains("-xcontext-parameters"), removed.getMessage());

        BuildResult restored = build(service, true);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("context-1", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean preview) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(preview),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source(boolean contextParameters) throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/ContextParameterApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, contextParameters
                ? """
                        package com.example

                        interface UserService {
                            fun findUserById(id: Int): String
                        }

                        context(users: UserService)
                        fun firstUser(): String = users.findUserById(1)

                        object ContextParameterApi {
                            @JvmStatic
                            fun message(): String {
                                val users = object : UserService {
                                    override fun findUserById(id: Int): String = "context-$id"
                                }
                                return context(users) { firstUser() }
                            }
                        }
                        """
                : """
                        package com.example

                        interface UserService {
                            fun findUserById(id: Int): String
                        }

                        fun firstUser(users: UserService): String = users.findUserById(1)

                        object ContextParameterApi {
                            @JvmStatic
                            fun message(): String {
                                val users = object : UserService {
                                    override fun findUserById(id: Int): String = "context-$id"
                                }
                                return firstUser(users)
                            }
                        }
                        """);
    }

    private Object invoke(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return Class.forName("com.example.ContextParameterApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static String diagnostics(KotlinCompileException exception) {
        return exception.getMessage().toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(boolean preview) {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xcontext-parameters\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-context-parameters"
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
