package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;

final class MainCompilerToolchainResolverTest
        extends MainCompilerToolchainResolverTestSupport {
    @Test
    void selectsJavaWithoutResolvedPackageMetadata() {
        MainCompilerToolchain selected = mainResolver.resolve(
                javaSources(),
                config(GROOVY_VERSION, VERSION),
                null,
                false);

        assertEquals(MainCompilerToolchain.Language.JAVA, selected.language());
        assertTrue(selected.groovyToolchain().isEmpty());
        assertTrue(selected.kotlinToolchain().isEmpty());
    }

    @Test
    void preservesLegacyGroovyResolutionWhenNoToolchainVersionIsConfigured() throws IOException {
        VerifiedJar groovy = groovyJar();

        MainCompilerToolchain selected = mainResolver.resolve(
                groovySources(),
                config("", ""),
                legacyGroovyPackages(groovy),
                true);

        assertEquals(MainCompilerToolchain.Language.GROOVY, selected.language());
        assertEquals(GROOVY_VERSION, selected.groovyToolchain().orElseThrow().version());
        assertEquals(groovy.sha256(), selected.groovyToolchain().orElseThrow().sha256());
        assertTrue(selected.kotlinToolchain().isEmpty());
        assertFalse(selected.groovyToolchain().orElseThrow().identity().contains("|launcher="));
    }

    @Test
    void delegatesConfiguredGroovyResolutionToTheIsolatedToolClosure() throws IOException {
        VerifiedJar groovy = groovyJar();

        MainCompilerToolchain selected = mainResolver.resolve(
                groovySources(),
                config(GROOVY_VERSION, ""),
                configuredGroovyPackages(groovy),
                true);

        assertEquals(MainCompilerToolchain.Language.GROOVY, selected.language());
        assertTrue(selected.groovyToolchain().orElseThrow().identity().contains("|launcher=sha256:"));
        assertTrue(selected.kotlinToolchain().isEmpty());
    }

    @Test
    void selectsConfiguredKotlinCompilerAndIndependentRuntime() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "ordinary-kotlin-runtime");

        MainCompilerToolchain selected = mainResolver.resolve(
                kotlinSources(),
                config("", VERSION),
                validPackages(root, runtime),
                true);

        assertEquals(MainCompilerToolchain.Language.KOTLIN, selected.language());
        assertTrue(selected.groovyToolchain().isEmpty());
        assertEquals(VERSION, selected.kotlinToolchain().orElseThrow().version());
        assertEquals(root.sha256(), selected.kotlinToolchain().orElseThrow().sha256());
    }

    @Test
    void passesConfiguredKotlinVersionToTheResolver() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "ordinary-kotlin-runtime");

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> mainResolver.resolve(
                        kotlinSources(),
                        config("", ""),
                        validPackages(root, runtime),
                        true));

        assertTrue(failure.getMessage().contains("`[toolchain.kotlin].version` is required"));
    }
}
