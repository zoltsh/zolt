package sh.zolt.toml.manifest.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeBuildConfigurationWithNullIndex;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeBuildDomains;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ManifestBuildTestSupport.Decoded;

final class ManifestBuildDomainObserverTest {
    @Test
    void observesCanonicalCumulativeBuildDomainSnapshots() {
        ArrayList<Decoded> observed = new ArrayList<>();

        Decoded complete = decodeBuildDomains("""
                [coverage]
                line = 80

                [test.runtime]
                events = ["failed"]

                [generated.main.api]
                kind = "openapi"
                input = "api.yaml"

                [resources]
                main = ["resources/zeta", "resources/alpha"]

                [compiler]
                args = ["-parameters", "-Xlint:all"]
                encoding = "UTF-8"

                [build]
                sources = ["src/zeta/java", "src/alpha/java"]
                """, observed::add);

        assertEquals(5, observed.size());
        Decoded build = observed.get(0);
        assertEquals(1, build.build().build().orElseThrow().sources().size());
        assertTrue(build.build().compiler().isEmpty());
        assertTrue(build.generated().isEmpty());

        Decoded compiler = observed.get(1);
        assertEquals(2, compiler.build().build().orElseThrow().sources().size());
        assertEquals("UTF-8", compiler.build().compiler().orElseThrow().encoding().orElseThrow());
        assertTrue(compiler.build().compiler().orElseThrow().args().isEmpty());

        Decoded resources = observed.get(2);
        assertEquals(2, resources.build().compiler().orElseThrow().args().size());
        assertEquals(AuthoredResources.empty(), resources.build().resources().orElseThrow());

        Decoded generated = observed.get(3);
        assertEquals(2, generated.build().resources().orElseThrow().main().size());
        assertEquals(AuthoredGeneratedSources.empty(), generated.generated().orElseThrow());
        assertTrue(generated.build().tests().isEmpty());

        Decoded tests = observed.get(4);
        assertEquals(1, tests.generated().orElseThrow().main().size());
        assertEquals(AuthoredTests.empty(), tests.build().tests().orElseThrow());
        for (Decoded snapshot : observed) {
            assertTrue(snapshot.build().coverage().isEmpty());
        }
        assertTrue(complete.build().coverage().isPresent());
        assertEquals(1, complete.build().tests().orElseThrow().runtime().orElseThrow().events().size());
        assertEquals(1, complete.generated().orElseThrow().main().size());
        assertThrows(
                UnsupportedOperationException.class,
                build.build().build().orElseThrow().sources()::clear);
        assertThrows(
                UnsupportedOperationException.class,
                tests.generated().orElseThrow().main()::clear);
    }

    @Test
    void retainsEachChildObserverAnchorBeforeLaterFailures() {
        assertObservedFailure(
                "[build]\nsources = [\"src/main/java\", \"src/main/java\"]\n",
                "`build.sources`");
        assertObservedFailure("""
                [compiler]
                args = ["--release=21"]
                encoding = "UTF-8"
                """, "`compiler.encoding`");
        assertObservedFailure(
                "[resources]\nmain = [\"resources\", \"resources\"]\n",
                "`resources.main`");
        assertObservedFailure("""
                [generated.main.invalid]
                kind = "openapi"
                """, "[generated.main]");
        assertObservedFailure(
                "test.runtime.events = [\"failed\"]\n",
                "`test.runtime.events`");
    }

    @Test
    void continuesLeafValidationAfterNonthrowingObservation() {
        assertObservedThenLeafFailure(
                "[build]\nsources = [\"src/main/java\", \"src/main/java\"]\n",
                "`build.sources`");
        assertObservedThenLeafFailure(
                "[compiler]\nargs = [\"-parameters\", \"--release=21\"]\n",
                "`compiler.args[1]`");
        assertObservedThenLeafFailure(
                "[resources]\nmain = [\"resources\", \"resources\"]\n",
                "`resources.main[1]`");
        assertObservedThenLeafFailure("""
                [generated.presets.invalid]
                kind = "openapi"
                generator = " "
                """, "`generated.presets.invalid.generator`");
        assertObservedThenLeafFailure(
                "test.runtime.jvmArgs = [\"-ea\", \"${project.root}\"]\n",
                "`test.runtime.jvmArgs[1]`");
        assertObservedThenLeafFailure("""
                [coverage]
                line = 101
                [build]
                sources = ["src/main/java"]
                """, "`coverage.line`");
    }

    @Test
    void retainsEarlierSnapshotsWhenALaterDomainFailsBeforePresence() {
        ArrayList<Decoded> observed = new ArrayList<>();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeBuildDomains("""
                        [compiler]
                        args = ["--release=21"]
                        [build]
                        sources = ["src/main/java"]
                        """, observed::add));

        assertTrue(failure.getMessage().contains("`compiler.args[0]`"));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(1, observed.size());
        assertTrue(observed.getFirst().build().build().isPresent());
        assertTrue(observed.getFirst().build().compiler().isEmpty());
    }

    @Test
    void skipsOmissionCoverageAndShapeFailuresAndRequiresInputs() {
        AtomicInteger observations = new AtomicInteger();

        Decoded omitted = decodeBuildDomains("", ignored -> observations.incrementAndGet());
        assertEquals(AuthoredBuildConfiguration.empty(), omitted.build());
        Decoded coverage = decodeBuildDomains(
                "[coverage]\nline = 80\n", ignored -> observations.incrementAndGet());
        assertTrue(coverage.build().coverage().isPresent());
        assertEquals(0, observations.get());

        ZoltConfigException shapeFailure = assertThrows(
                ZoltConfigException.class,
                () -> decodeBuildDomains(
                        "[build]\nsources = [\"/absolute\"]\n",
                        ignored -> observations.incrementAndGet()));
        assertTrue(shapeFailure.getMessage().contains("`build.sources`"));
        assertNull(shapeFailure.getCause());
        assertEquals(0, observations.get());

        assertEquals(
                "Manifest decode index is required.",
                assertThrows(
                                NullPointerException.class,
                                () -> decodeBuildConfigurationWithNullIndex())
                        .getMessage());
        assertEquals(
                "Authored build domain observer is required.",
                assertThrows(
                                NullPointerException.class,
                                () -> decodeBuildDomains("", null))
                        .getMessage());
    }

    private static void assertObservedFailure(String source, String anchor) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeBuildDomains(source, ignored -> {
                    throw new IllegalArgumentException("Observed authored build domains.");
                }));
        assertTrue(failure.getMessage().contains(anchor), failure.getMessage());
        assertTrue(failure.getMessage().contains("Observed authored build domains."));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static void assertObservedThenLeafFailure(String source, String path) {
        AtomicInteger observations = new AtomicInteger();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeBuildDomains(source, ignored -> observations.incrementAndGet()));

        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(1, observations.get());
    }
}
