package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.build.BuildException;

final class MainCompilerToolchainResolverFailureTest
        extends MainCompilerToolchainResolverTestSupport {
    @Test
    void groovyRequiresVerifiedMetadataWithActionableApiRemediation() {
        BuildException failure = assertThrows(
                BuildException.class,
                () -> mainResolver.resolve(
                        groovySources(),
                        config("", ""),
                        List.of(),
                        false));

        assertTrue(failure.actionableError().summary().startsWith(
                "Groovy main compilation requires verified resolved package metadata"));
        assertTrue(failure.actionableError().remediation().contains(GroovyCompilerToolchain.COORDINATE));
        assertTrue(failure.actionableError().remediation().contains("cache-root build overload"));
        assertTrue(failure.actionableError().remediation().contains("metadata-aware workspace build API"));
    }

    @Test
    void kotlinRequiresVerifiedMetadataWithActionableApiRemediation() {
        BuildException failure = assertThrows(
                BuildException.class,
                () -> mainResolver.resolve(
                        kotlinSources(),
                        config("", VERSION),
                        List.of(),
                        false));

        assertTrue(failure.actionableError().summary().startsWith(
                "Kotlin main compilation requires verified resolved package metadata"));
        assertTrue(failure.actionableError().remediation().contains(KotlinCompilerToolchain.COORDINATE));
        assertTrue(failure.actionableError().remediation().contains("cache-root build overload"));
        assertTrue(failure.actionableError().remediation().contains("metadata-aware workspace build API"));
    }

    @Test
    void mixedNonJavaLanguagesFailBeforeMetadataOrResolverSelection() {
        BuildException failure = assertThrows(
                BuildException.class,
                () -> mainResolver.resolve(
                        mixedSources(),
                        config(GROOVY_VERSION, VERSION),
                        List.of(),
                        false));

        assertEquals(
                "The main source set combines Groovy and Kotlin, which Zolt does not support.",
                failure.actionableError().summary());
        assertTrue(failure.actionableError().remediation().contains("removing either the Groovy or Kotlin sources"));
    }
}
