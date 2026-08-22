package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/**
 * A publishing manifest asserted to reach the expected {@link PublishSettings} through the final
 * boundary.
 */
final class ManifestPublishSettingsAdapterTest {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @Test
    void publishingReachesThePublishSettings() {
        PublishSettings adapted = ManifestPublishSettingsAdapter.adapt(loader
                .document("""
                        [project]
                        name = "example-library"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21

                        [credentials.company]
                        usernameEnv = "MAVEN_USERNAME"
                        passwordEnv = "MAVEN_PASSWORD"

                        [publish]
                        release = "company-releases"
                        snapshot = "company-snapshots"

                        [publish.repositories.company-releases]
                        url = "https://repo.example.com/releases"
                        credentials = "company"

                        [publish.repositories.company-snapshots]
                        url = "https://repo.example.com/snapshots"
                        credentials = "company"

                        [publish.signing]
                        method = "gpg"
                        keyId = "3AB1C2D3E4F5A6B7"
                        passphraseEnv = "ZOLT_SIGNING_PASSPHRASE"

                        [publish.central]
                        tokenEnv = "ZOLT_CENTRAL_TOKEN"
                        mode = "automatic"
                        name = "example-library-1.0.0"
                        url = "https://central.sonatype.com"
                        """)
                .authored()
                .publishing());

        assertTrue(adapted.configured());
        assertEquals(List.of("main"), adapted.artifacts());
        assertEquals("company-releases", adapted.releaseRepository());
        assertEquals("company-snapshots", adapted.snapshotRepository());
        assertEquals(
                "https://repo.example.com/releases",
                adapted.repositories().get("company-releases").url());
        assertEquals(
                Optional.of("company"), adapted.repositories().get("company-releases").credentials());
        assertEquals(
                "https://repo.example.com/snapshots",
                adapted.repositories().get("company-snapshots").url());
        assertTrue(adapted.signing().enabled());
        assertEquals(Optional.of("3AB1C2D3E4F5A6B7"), adapted.signing().keyId());
        assertEquals(Optional.of("ZOLT_SIGNING_PASSPHRASE"), adapted.signing().passphraseEnv());
        assertTrue(adapted.central().configured());
        assertEquals(Optional.of("ZOLT_CENTRAL_TOKEN"), adapted.central().tokenEnv());
        assertEquals(CentralPublishingType.AUTOMATIC, adapted.central().publishingType());
        assertEquals(Optional.of("example-library-1.0.0"), adapted.central().deploymentName());
        assertEquals("https://central.sonatype.com", adapted.central().baseUrl());
    }

    @Test
    void manualCentralModeMapsToUserManagedPublishing() {
        PublishSettings adapted = ManifestPublishSettingsAdapter.adapt(loader
                .document("""
                        [project]
                        name = "example-library"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21

                        [publish.central]
                        tokenEnv = "ZOLT_CENTRAL_TOKEN"
                        mode = "manual"
                        """)
                .authored()
                .publishing());

        assertEquals(CentralPublishingType.USER_MANAGED, adapted.central().publishingType());
        assertEquals(
                PublishCentralSettings.DEFAULT_BASE_URL,
                adapted.central().baseUrl(),
                "design §14.3 leaves the public Central URL implicit");
        assertEquals(Optional.of("ZOLT_CENTRAL_TOKEN"), adapted.central().tokenEnv());
    }

    @Test
    void absentPublishingDomainIsUnconfigured() {
        PublishSettings adapted = ManifestPublishSettingsAdapter.adapt(loader
                .document("""
                        [project]
                        name = "example-library"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21
                        """)
                .authored()
                .publishing());

        assertEquals(new PublishSettings("", "", List.of(), Map.of()), adapted);
    }
}
