package sh.zolt.toml.manifest.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeCompiler;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeCompilerWithNullIndex;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeCompilerWithNullObserver;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.toml.ZoltConfigException;

final class ManifestCompilerPresenceObserverTest {
    @Test
    void observesOnlyTheFirstCanonicalImmutablePrefix() {
        ArrayList<AuthoredCompiler> observed = new ArrayList<>();

        AuthoredCompiler complete = decodeCompiler("""
                [compiler.generated]
                test = "generated/tests"
                main = "generated/main"
                [compiler.test]
                args = ["-g", "-Xlint:none"]
                jdkApi = "host"
                [compiler]
                args = ["-parameters", "-Xlint:all"]
                jdkApi = "release"
                encoding = "UTF-8"
                """, observed::add).orElseThrow();

        assertEquals(1, observed.size());
        AuthoredCompiler first = observed.getFirst();
        assertEquals("UTF-8", first.encoding().orElseThrow());
        assertTrue(first.jdkApi().isEmpty());
        assertTrue(first.args().isEmpty());
        assertTrue(first.test().isEmpty());
        assertTrue(first.generated().isEmpty());
        assertEquals(2, complete.args().size());
        assertEquals(2, complete.test().orElseThrow().args().size());
        assertTrue(complete.generated().isPresent());
    }

    @Test
    void retainsCanonicalPrefixAnchorsAndPreemptsLaterModelFailures() {
        assertObservedFailure("[compiler]\njdkApi = \"host\"\n", "`compiler.jdkApi`");
        assertObservedFailure(
                "[compiler]\nargs = [\"-parameters\", \"--release=21\"]\n",
                "`compiler.args[0]`");
        assertObservedFailure("""
                [compiler.test]
                args = ["@javac.options"]
                jdkApi = "host"
                """, "`compiler.test.jdkApi`");
        assertObservedFailure(
                "[compiler.test]\nargs = [\"-g\", \"@javac.options\"]\n",
                "`compiler.test.args[0]`");
        assertObservedFailure("""
                [compiler.generated]
                test = "generated/tests"
                main = "generated/main"
                """, "`compiler.generated.main`");
    }

    @Test
    void defersEmptyMainArgumentsToTheirCanonicalField() {
        assertObservedFailure("""
                [compiler]
                args = []
                [compiler.test]
                args = ["@javac.options"]
                jdkApi = "host"
                """, "`compiler.args`");
        assertObservedFailure("""
                [compiler]
                args = []
                [compiler.test]
                args = ["-g"]
                """, "`compiler.args`");
        assertObservedFailure("""
                [compiler]
                args = []
                [compiler.generated]
                test = "generated/tests"
                """, "`compiler.args`");
    }

    @Test
    void leavesEmptyChildrenAndFirstSemanticFailuresUnobserved() {
        AtomicInteger observations = new AtomicInteger();
        assertSemanticFailure("[compiler]\nargs = []\n", observations, "`compiler.args`");
        assertSemanticFailure(
                "[compiler.test]\nargs = []\n",
                observations,
                "`compiler.test.args`");
        assertSemanticFailure("""
                [compiler]
                args = []
                [compiler.test]
                args = []
                """, observations, "`compiler.args`");
        assertSemanticFailure("""
                [compiler.test]
                args = []
                [compiler.generated]
                main = "generated/main"
                """, observations, "`compiler.test.args`");
        assertSemanticFailure(
                "[compiler]\nencoding = \" \"\n",
                observations,
                "`compiler.encoding`");
        assertSemanticFailure(
                "[compiler]\nargs = [\"--release=21\"]\n",
                observations,
                "`compiler.args[0]`");
        assertEquals(0, observations.get());
    }

    @Test
    void leavesShapeFailuresUnobserved() {
        AtomicInteger observations = new AtomicInteger();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeCompiler(
                        "[compiler.generated]\nmain = \"../generated\"\n",
                        ignored -> observations.incrementAndGet()));

        assertTrue(failure.getMessage().contains("`compiler.generated.main`"));
        assertNull(failure.getCause());
        assertEquals(0, observations.get());
    }

    @Test
    void doesNotObserveOmissionAndRequiresNonNullInputs() {
        AtomicInteger observations = new AtomicInteger();

        assertTrue(decodeCompiler("", ignored -> observations.incrementAndGet()).isEmpty());

        assertEquals(0, observations.get());
        assertThrows(NullPointerException.class, () -> decodeCompilerWithNullIndex());
        assertThrows(NullPointerException.class, () -> decodeCompilerWithNullObserver());
    }

    private static void assertObservedFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeCompiler(source, ignored -> {
                    throw new IllegalArgumentException("Observed authored compiler.");
                }));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("Observed authored compiler."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static void assertSemanticFailure(
            String source,
            AtomicInteger observations,
            String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeCompiler(source, ignored -> observations.incrementAndGet()));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
