package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
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
    @Test
    void resolvesTheExpectedVersionAlignedSerializationPlugin() throws IOException {
        VerifiedJar compiler = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar plugin = plainJar(SERIALIZATION_PLUGIN, VERSION, "serialization-plugin");

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
        VerifiedJar plugin = plainJar(SERIALIZATION_PLUGIN, VERSION, "serialization-plugin");

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
