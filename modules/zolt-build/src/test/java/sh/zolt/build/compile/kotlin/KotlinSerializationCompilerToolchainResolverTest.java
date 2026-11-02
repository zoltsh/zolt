package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompilerToolchain;
import sh.zolt.build.compile.KotlinCompilerToolchainResolverTestSupport;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;

final class KotlinSerializationCompilerToolchainResolverTest
        extends KotlinCompilerToolchainResolverTestSupport {
    private static final String REGISTRAR =
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar";

    @Test
    void resolvesTheExpectedVersionAlignedSerializationPlugin() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar plugin = serializationPluginJar(
                "kotlinx-serialization-compiler-plugin.embeddable",
                VERSION + "-release-294",
                true);

        KotlinCompilerToolchain toolchain = resolver.resolve(
                packages(compiler, runtime, plugin, VERSION),
                VERSION,
                KotlinCompilationScope.MAIN,
                Set.of(KotlinCompilerPlugin.SERIALIZATION));

        assertEquals(
                List.of(plugin.path().toAbsolutePath().normalize()),
                toolchain.compilerPluginJars());
        assertTrue(toolchain.launcherClasspath().entries().contains(
                plugin.path().toAbsolutePath().normalize()));
        assertFalse(toolchain.identity().contains(tempDir.toString()));
    }

    @Test
    void requiresConfigurationAndAnExactAlignedDirectRoot() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar plugin = serializationPluginJar(
                "kotlinx-serialization-compiler-plugin.embeddable",
                VERSION,
                true);

        assertMessageContains(
                () -> resolver.resolve(packages(compiler, runtime, plugin, VERSION), VERSION),
                "extra direct roots in scope `tool-kotlin`");
        assertMessageContains(
                () -> resolver.resolve(
                        validPackages(compiler, runtime),
                        VERSION,
                        KotlinCompilationScope.MAIN,
                        Set.of(KotlinCompilerPlugin.SERIALIZATION)),
                "no direct " + SERIALIZATION_PLUGIN);
        assertMessageContains(
                () -> resolver.resolve(
                        packages(compiler, runtime, plugin, "2.2.1"),
                        VERSION,
                        KotlinCompilationScope.MAIN,
                        Set.of(KotlinCompilerPlugin.SERIALIZATION)),
                "does not match zolt.lock serialization compiler plugin tool root version `2.2.1`");
    }

    @Test
    void rejectsSerializationJarsWithoutTheOfficialRegistrarMetadata() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");

        assertPluginFailure(
                compiler,
                runtime,
                serializationPluginJar(
                        "kotlinx-serialization-compiler-plugin.embeddable", VERSION, false),
                "does not contain " + REGISTRAR);
        assertPluginFailure(
                compiler,
                runtime,
                serializationPluginJar("not-serialization", VERSION, true),
                "Implementation-Title `not-serialization`");
        assertPluginFailure(
                compiler,
                runtime,
                serializationPluginJar(
                        "kotlinx-serialization-compiler-plugin.embeddable", "2.2.1", true),
                "Implementation-Version `2.2.1`");
    }

    private List<ResolvedClasspathPackage> packages(
            VerifiedJar compiler,
            VerifiedJar runtime,
            VerifiedJar plugin,
            String pluginVersion) {
        return validPackages(
                compiler,
                runtime,
                dependency(
                        SERIALIZATION_PLUGIN,
                        plugin,
                        pluginVersion,
                        DependencyScope.TOOL_KOTLIN,
                        true,
                        NestedArtifactIdentity.external(SERIALIZATION_PLUGIN, pluginVersion)));
    }

    private VerifiedJar serializationPluginJar(
            String implementationTitle,
            String implementationVersion,
            boolean includeRegistrar) throws IOException {
        Path jar = tempDir.resolve("artifacts/kotlin-serialization-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(
                Attributes.Name.IMPLEMENTATION_TITLE,
                implementationTitle);
        manifest.getMainAttributes().put(
                Attributes.Name.IMPLEMENTATION_VERSION,
                implementationVersion);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (includeRegistrar) {
                writeEntry(
                        output,
                        REGISTRAR,
                        "org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar"
                                .getBytes(StandardCharsets.UTF_8));
            }
        }
        return verifiedArtifact(jar, SERIALIZATION_PLUGIN, VERSION);
    }

    private void assertPluginFailure(
            VerifiedJar compiler,
            VerifiedJar runtime,
            VerifiedJar plugin,
            String expected) {
        assertMessageContains(
                () -> resolver.resolve(
                        packages(compiler, runtime, plugin, VERSION),
                        VERSION,
                        KotlinCompilationScope.MAIN,
                        Set.of(KotlinCompilerPlugin.SERIALIZATION)),
                expected);
    }

    private static void assertMessageContains(
            ThrowingAction action,
            String expected) {
        KotlinCompileException failure = assertThrows(KotlinCompileException.class, action::run);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
