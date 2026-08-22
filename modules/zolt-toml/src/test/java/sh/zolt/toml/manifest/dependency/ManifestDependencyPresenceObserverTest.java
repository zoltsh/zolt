package sh.zolt.toml.manifest.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.constructDependencyDomainsWithNullConstraints;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.constructDependencyDomainsWithNullDependencies;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.constructDependencyDomainsWithNullPolicy;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.decodeDependencies;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.decodeDependenciesWithNullIndex;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.decodeDependenciesWithNullObserver;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.decodeLicensePolicy;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.decodePolicy;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ManifestDependencyTestSupport.Decoded;

final class ManifestDependencyPresenceObserverTest {
    @Test
    void coordinatorObservesCanonicalCumulativeSnapshots() {
        ArrayList<Decoded> observed = new ArrayList<>();

        Decoded complete = decodeDependencies("""
                [dependencies.policy]
                deny = [{ coordinate = "org.example:blocked" }]
                conflicts = "resolve"

                [dependencies.constraints]
                "org.example:constraint" = "1.0"

                [dependencies]
                "org.example:library" = "1.0"
                """, observed::add);

        assertEquals(3, observed.size());
        assertTrue(observed.get(0).dependencies().orElseThrow().declarations().isEmpty());
        assertTrue(observed.get(0).constraints().isEmpty());
        assertTrue(observed.get(0).policy().isEmpty());
        assertEquals(
                1, observed.get(1).dependencies().orElseThrow().declarations().size());
        assertTrue(observed.get(1).constraints().orElseThrow().entries().isEmpty());
        assertTrue(observed.get(1).policy().isEmpty());
        assertEquals(
                1, observed.get(2).dependencies().orElseThrow().declarations().size());
        assertEquals(1, observed.get(2).constraints().orElseThrow().entries().size());
        AuthoredDependencyPolicy policy = observed.get(2).policy().orElseThrow();
        assertEquals(DependencyConflictPolicy.RESOLVE, policy.conflicts().orElseThrow());
        assertTrue(policy.deny().isEmpty());
        assertEquals(1, complete.policy().orElseThrow().deny().size());
    }

    @Test
    void coordinatorRetainsLeafAnchorsAndPreemptsLaterFailures() {
        assertObservedFailure("""
                [dependencies.policy]
                deny = [{ coordinate = "invalid" }]
                [dependencies.constraints]
                "org.example:constraint" = "LATEST"
                [dependencies]
                "org.example:library" = "LATEST"
                """, "Invalid manifest section `[dependencies]`");
        assertObservedFailure("""
                [dependencies.policy]
                deny = [{ coordinate = "invalid" }]
                [dependencies.constraints]
                "org.example:constraint" = "LATEST"
                """, "Invalid manifest section `[dependencies.constraints]`");
        assertObservedFailure("""
                [dependencies.policy]
                deny = [{ coordinate = "invalid" }]
                conflicts = "resolve"
                """, "Invalid value for `dependencies.policy.conflicts`");
    }

    @Test
    void coordinatorDoesNotObserveOmissionAndRequiresNonNullInputs() {
        AtomicInteger observations = new AtomicInteger();

        Decoded decoded = decodeDependencies("", ignored -> observations.incrementAndGet());

        assertTrue(decoded.dependencies().isEmpty());
        assertEquals(0, observations.get());
        assertThrows(NullPointerException.class, () -> decodeDependenciesWithNullIndex());
        assertThrows(NullPointerException.class, () -> decodeDependenciesWithNullObserver());
    }

    @Test
    void coordinatorDecodedRequiresNonNullComponents() {
        assertThrows(
                NullPointerException.class,
                () -> constructDependencyDomainsWithNullDependencies());
        assertThrows(
                NullPointerException.class,
                () -> constructDependencyDomainsWithNullConstraints());
        assertThrows(
                NullPointerException.class,
                () -> constructDependencyDomainsWithNullPolicy());
    }

    @Test
    void dependencyPolicyObserverFiresOnceWithTheFirstCanonicalPartial() {
        AtomicInteger observations = new AtomicInteger();
        AtomicReference<AuthoredDependencyPolicy> first = new AtomicReference<>();

        decodePolicy("""
                [dependencies.policy]
                deny = [{ coordinate = "org.example:blocked" }]
                conflicts = "resolve"

                [dependencies.policy.licenses]
                allow = ["MIT"]
                unknown = "warn"
                """, policy -> {
                    observations.incrementAndGet();
                    first.compareAndSet(null, policy);
                });

        assertEquals(1, observations.get());
        assertTrue(first.get().conflicts().isPresent());
        assertTrue(first.get().deny().isEmpty());
        assertTrue(first.get().licenses().isEmpty());
    }

    @Test
    void licensePolicyObserverFiresOnceWithTheFirstCanonicalPartial() {
        AtomicInteger observations = new AtomicInteger();
        AtomicReference<AuthoredLicensePolicy> first = new AtomicReference<>();

        decodeLicensePolicy("""
                [dependencies.policy.licenses]
                unknown = "warn"
                deny = ["GPL-3.0-only"]
                allow = ["MIT"]
                """, policy -> {
                    observations.incrementAndGet();
                    first.compareAndSet(null, policy);
                });

        assertEquals(1, observations.get());
        assertEquals("MIT", first.get().allow().getFirst().value());
        assertTrue(first.get().deny().isEmpty());
        assertTrue(first.get().unknown().isEmpty());
    }

    private static void assertObservedFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeDependencies(source, ignored -> {
                    throw new IllegalArgumentException("Observed dependency domains.");
                }));

        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("Observed dependency domains."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
