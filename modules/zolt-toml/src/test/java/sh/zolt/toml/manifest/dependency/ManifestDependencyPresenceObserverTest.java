package sh.zolt.toml.manifest.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.decodeLicensePolicy;
import static sh.zolt.toml.manifest.ManifestDependencyTestSupport.decodePolicy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;

final class ManifestDependencyPresenceObserverTest {
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
}
