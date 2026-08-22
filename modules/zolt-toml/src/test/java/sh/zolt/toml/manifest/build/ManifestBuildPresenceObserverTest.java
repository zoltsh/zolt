package sh.zolt.toml.manifest.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeBuild;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeBuildWithNullIndex;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeBuildWithNullObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.toml.ZoltConfigException;

final class ManifestBuildPresenceObserverTest {
    @Test
    void observesOnlyTheFirstCanonicalSourcesPrefix() {
        ArrayList<AuthoredBuild> observed = new ArrayList<>();

        AuthoredBuild complete = decodeBuild("""
                [build.metadata]
                git = false
                [build.output]
                test = "test-classes"
                [build]
                sources = ["src/zeta/java", "src/alpha/java"]
                """, observed::add).orElseThrow();

        assertEquals(1, observed.size());
        assertEquals(
                List.of(new ManifestRelativePath("src/zeta/java")),
                observed.getFirst().sources());
        assertTrue(observed.getFirst().output().isEmpty());
        assertTrue(observed.getFirst().metadata().isEmpty());
        assertEquals(2, complete.sources().size());
        assertTrue(complete.output().isPresent());
        assertTrue(complete.metadata().isPresent());
    }

    @Test
    void retainsTheEarliestGenuinePrefixAnchorAndPreemptsLaterFailures() {
        assertObservedFailure("""
                [build]
                sources = ["src/main/java", "src/main/java"]
                """, "`build.sources`");
        assertObservedFailure("""
                [build.output]
                main = "classes"
                root = "target"
                """, "`build.output.root`");
        assertObservedFailure("""
                [build.metadata]
                git = false
                """, "`build.metadata.git`");
    }

    @Test
    void defersEmptySourcesAndRetainsTheirCanonicalAnchor() {
        assertObservedFailure("""
                [build]
                sources = []
                [build.output]
                main = "classes"
                root = "target"
                """, "`build.sources`");
        assertObservedFailure("""
                [build]
                sources = []
                [build.metadata]
                git = false
                """, "`build.sources`");
    }

    @Test
    void leavesShapeFailuresAndEmptyOnlyBuildsUnobserved() {
        AtomicInteger observations = new AtomicInteger();
        ZoltConfigException shapeFailure = assertThrows(
                ZoltConfigException.class,
                () -> decodeBuild(
                        "[build]\nsources = [\"/absolute\"]\n",
                        ignored -> observations.incrementAndGet()));
        assertTrue(
                shapeFailure.getMessage().contains("`build.sources`"),
                shapeFailure.getMessage());
        assertNull(shapeFailure.getCause());
        assertLeafFailure(
                "[build]\nsources = []\n",
                observations,
                "`build.sources`");
        assertEquals(0, observations.get());
    }

    @Test
    void doesNotObserveOmissionAndRequiresNonNullInputs() {
        AtomicInteger observations = new AtomicInteger();

        assertTrue(decodeBuild("", ignored -> observations.incrementAndGet()).isEmpty());

        assertEquals(0, observations.get());
        assertThrows(NullPointerException.class, () -> decodeBuildWithNullIndex());
        assertThrows(NullPointerException.class, () -> decodeBuildWithNullObserver());
    }

    private static void assertObservedFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeBuild(source, ignored -> {
                    throw new IllegalArgumentException("Observed authored build.");
                }));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("Observed authored build."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static void assertLeafFailure(
            String source,
            AtomicInteger observations,
            String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeBuild(source, ignored -> observations.incrementAndGet()));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
