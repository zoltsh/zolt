package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.doctor.JdkStatus;

final class MainCompilerToolchainTest {
    private static final String ROOT_HASH = "a".repeat(64);
    private static final String CLOSURE_HASH = "sha256:" + "b".repeat(64);

    @Test
    void eachSelectionHasExactlyOneActiveLanguagePayload() {
        MainCompilerToolchain java = MainCompilerToolchain.javaOnly();
        MainCompilerToolchain groovy = MainCompilerToolchain.groovy(
                new GroovyCompilerToolchain("4.0.22", ROOT_HASH, Path.of("groovy.jar")));
        MainCompilerToolchain kotlin = MainCompilerToolchain.kotlin(
                kotlin(ROOT_HASH, CLOSURE_HASH, Path.of("kotlin.jar")));

        assertEquals(MainCompilerToolchain.Language.JAVA, java.language());
        assertTrue(java.groovyToolchain().isEmpty());
        assertTrue(java.kotlinToolchain().isEmpty());
        assertEquals(MainCompilerToolchain.Language.GROOVY, groovy.language());
        assertTrue(groovy.groovyToolchain().isPresent());
        assertTrue(groovy.kotlinToolchain().isEmpty());
        assertEquals(MainCompilerToolchain.Language.KOTLIN, kotlin.language());
        assertTrue(kotlin.groovyToolchain().isEmpty());
        assertTrue(kotlin.kotlinToolchain().isPresent());
    }

    @Test
    void delegatesJavaAndGroovyIdentityToExistingStableForms() {
        GroovyCompilerToolchain groovyToolchain =
                new GroovyCompilerToolchain("4.0.22", ROOT_HASH, Path.of("groovy.jar"));

        assertEquals(
                EffectiveCompilerIdentity.of(jdkStatus()),
                MainCompilerToolchain.javaOnly().compilerIdentity(jdkStatus()));
        assertEquals(
                EffectiveCompilerIdentity.of(jdkStatus(), groovyToolchain),
                MainCompilerToolchain.groovy(groovyToolchain).compilerIdentity(jdkStatus()));
    }

    @Test
    void kotlinIdentityIsRelocatableAndTracksCompilerContentAndSemantics() {
        KotlinCompilerToolchain original = kotlin(ROOT_HASH, CLOSURE_HASH, Path.of("first/kotlin.jar"));
        KotlinCompilerToolchain relocated = kotlin(ROOT_HASH, CLOSURE_HASH, Path.of("elsewhere/kotlin.jar"));
        KotlinCompilerToolchain changed = kotlin("c".repeat(64), CLOSURE_HASH, Path.of("first/kotlin.jar"));
        String identity = MainCompilerToolchain.kotlin(original).compilerIdentity(jdkStatus());

        assertEquals(
                identity,
                MainCompilerToolchain.kotlin(relocated).compilerIdentity(jdkStatus()));
        assertNotEquals(
                identity,
                MainCompilerToolchain.kotlin(changed).compilerIdentity(jdkStatus()));
        assertEquals(
                EffectiveCompilerIdentity.of(
                        jdkStatus(),
                        "kotlinCompiler",
                        original.identity(),
                        "kotlin-main-v1"),
                identity);
        assertNotEquals(
                EffectiveCompilerIdentity.of(
                        jdkStatus(),
                        "kotlinCompiler",
                        original.identity()),
                identity);
    }

    private static KotlinCompilerToolchain kotlin(
            String rootHash,
            String closureIdentity,
            Path jar) {
        return new KotlinCompilerToolchain(
                "2.2.0",
                rootHash,
                List.of(jar),
                closureIdentity);
    }

    private static JdkStatus jdkStatus() {
        return new JdkStatus(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("21.0.11"),
                Optional.of("jdk-distribution-identity"),
                "21");
    }
}
