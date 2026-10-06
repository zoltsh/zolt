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
import sh.zolt.dependency.PackageId;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;

final class KotlinSpringCompilerToolchainResolverTest
        extends KotlinCompilerToolchainResolverTestSupport {
    private static final PackageId ALL_OPEN = new PackageId(
            "org.jetbrains.kotlin", "kotlin-allopen-compiler-plugin-embeddable");
    private static final String REGISTRAR =
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar";
    private static final String COMMAND_LINE_PROCESSOR =
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor";

    @Test
    void resolvesSpringAsAnExactVersionAlignedAllOpenPreset() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar allOpen = pluginJar(
                ALL_OPEN,
                "kotlin-allopen-compiler-plugin.embeddable",
                VERSION + "-release-294",
                true,
                true);

        KotlinCompilerToolchain toolchain = resolver.resolve(
                packages(compiler, runtime, ALL_OPEN, allOpen, VERSION),
                VERSION,
                KotlinCompilationScope.MAIN,
                Set.of(KotlinCompilerPlugin.SPRING));

        assertEquals(
                List.of(allOpen.path().toAbsolutePath().normalize()),
                toolchain.compilerPluginJars());
        assertEquals(
                List.of(new KotlinCompilerPluginOption(
                        "org.jetbrains.kotlin.allopen", "preset", "spring")),
                toolchain.compilerPluginOptions());
        assertTrue(toolchain.launcherClasspath().entries().contains(
                allOpen.path().toAbsolutePath().normalize()));
        assertFalse(toolchain.identity().contains(tempDir.toString()));
    }

    @Test
    void keepsMultiplePluginsInOwnedDeterministicOrder() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar allOpen = pluginJar(
                ALL_OPEN,
                "kotlin-allopen-compiler-plugin.embeddable",
                VERSION,
                true,
                true);
        VerifiedJar serialization = pluginJar(
                SERIALIZATION_PLUGIN,
                "kotlinx-serialization-compiler-plugin.embeddable",
                VERSION,
                true,
                false);

        KotlinCompilerToolchain toolchain = resolver.resolve(
                validPackages(
                        compiler,
                        runtime,
                        pluginDependency(ALL_OPEN, allOpen, VERSION),
                        pluginDependency(SERIALIZATION_PLUGIN, serialization, VERSION)),
                VERSION,
                KotlinCompilationScope.MAIN,
                Set.of(KotlinCompilerPlugin.SPRING, KotlinCompilerPlugin.SERIALIZATION));

        assertEquals(
                List.of(
                        serialization.path().toAbsolutePath().normalize(),
                        allOpen.path().toAbsolutePath().normalize()),
                toolchain.compilerPluginJars());
        assertEquals(1, toolchain.compilerPluginOptions().size());
        assertEquals(
                "plugin:org.jetbrains.kotlin.allopen:preset=spring",
                toolchain.compilerPluginOptions().getFirst().argument());
    }

    @Test
    void requiresConfigurationAndAnExactAlignedDirectRoot() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar allOpen = pluginJar(
                ALL_OPEN,
                "kotlin-allopen-compiler-plugin.embeddable",
                VERSION,
                true,
                true);

        assertMessageContains(
                () -> resolver.resolve(
                        packages(compiler, runtime, ALL_OPEN, allOpen, VERSION), VERSION),
                "extra direct roots in scope `tool-kotlin`");
        assertMessageContains(
                () -> resolver.resolve(
                        validPackages(compiler, runtime),
                        VERSION,
                        KotlinCompilationScope.MAIN,
                        Set.of(KotlinCompilerPlugin.SPRING)),
                "no direct " + ALL_OPEN);
        assertMessageContains(
                () -> resolver.resolve(
                        packages(compiler, runtime, ALL_OPEN, allOpen, "2.2.1"),
                        VERSION,
                        KotlinCompilationScope.MAIN,
                        Set.of(KotlinCompilerPlugin.SPRING)),
                "does not match zolt.lock Spring all-open compiler plugin tool root version `2.2.1`");
    }

    @Test
    void rejectsAllOpenJarsWithoutOfficialExecutableMetadata() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");

        assertPluginFailure(
                compiler,
                runtime,
                pluginJar(
                        ALL_OPEN,
                        "kotlin-allopen-compiler-plugin.embeddable",
                        VERSION,
                        false,
                        true),
                "does not contain " + REGISTRAR);
        assertPluginFailure(
                compiler,
                runtime,
                pluginJar(
                        ALL_OPEN,
                        "kotlin-allopen-compiler-plugin.embeddable",
                        VERSION,
                        true,
                        false),
                "does not contain " + COMMAND_LINE_PROCESSOR);
        assertPluginFailure(
                compiler,
                runtime,
                pluginJar(ALL_OPEN, "not-all-open", VERSION, true, true),
                "Implementation-Title `not-all-open`");
        assertPluginFailure(
                compiler,
                runtime,
                pluginJar(
                        ALL_OPEN,
                        "kotlin-allopen-compiler-plugin.embeddable",
                        "2.2.1",
                        true,
                        true),
                "Implementation-Version `2.2.1`");
    }

    private List<ResolvedClasspathPackage> packages(
            VerifiedJar compiler,
            VerifiedJar runtime,
            PackageId pluginId,
            VerifiedJar plugin,
            String pluginVersion) {
        return validPackages(
                compiler,
                runtime,
                pluginDependency(pluginId, plugin, pluginVersion));
    }

    private static ResolvedClasspathPackage pluginDependency(
            PackageId pluginId,
            VerifiedJar plugin,
            String pluginVersion) {
        return dependency(
                pluginId,
                plugin,
                pluginVersion,
                DependencyScope.TOOL_KOTLIN,
                true,
                NestedArtifactIdentity.external(pluginId, pluginVersion));
    }

    private VerifiedJar pluginJar(
            PackageId pluginId,
            String implementationTitle,
            String implementationVersion,
            boolean includeRegistrar,
            boolean includeCommandLineProcessor) throws IOException {
        Path jar = tempDir.resolve("artifacts/kotlin-plugin-" + jarSequence++ + ".jar");
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
                writeEntry(output, REGISTRAR, "registrar".getBytes(StandardCharsets.UTF_8));
            }
            if (includeCommandLineProcessor) {
                writeEntry(
                        output,
                        COMMAND_LINE_PROCESSOR,
                        "processor".getBytes(StandardCharsets.UTF_8));
            }
        }
        return verifiedArtifact(jar, pluginId, VERSION);
    }

    private void assertPluginFailure(
            VerifiedJar compiler,
            VerifiedJar runtime,
            VerifiedJar plugin,
            String expected) {
        assertMessageContains(
                () -> resolver.resolve(
                        packages(compiler, runtime, ALL_OPEN, plugin, VERSION),
                        VERSION,
                        KotlinCompilationScope.MAIN,
                        Set.of(KotlinCompilerPlugin.SPRING)),
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
