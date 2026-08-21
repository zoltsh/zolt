package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/**
 * A publishing manifest written twice — once in the legacy dialect, once in the final language —
 * asserted to produce the same legacy {@link PublishSettings}.
 *
 * <p>{@link #legacy} is the one helper the cleanup phase deletes with {@link PublishSettingsReader}.
 */
final class ManifestPublishSettingsAdapterTest {
    private static final Map<String, RepositoryCredentialSettings> CREDENTIALS = Map.of(
            "company", RepositoryCredentialSettings.basic("company", "MAVEN_USERNAME", "MAVEN_PASSWORD"));

    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    @Test
    void publishingPairIsEquivalent() {
        PublishSettings legacy = legacy(
                """
                [project]
                name = "example-library"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [publish]
                releaseRepository = "company-releases"
                snapshotRepository = "company-snapshots"

                [publish.repositories.company-releases]
                url = "https://repo.example.com/releases"
                credentials = "company"

                [publish.repositories.company-snapshots]
                url = "https://repo.example.com/snapshots"
                credentials = "company"

                [publish.signing]
                enabled = true
                keyId = "3AB1C2D3E4F5A6B7"
                passphraseEnv = "ZOLT_SIGNING_PASSPHRASE"

                [publish.central]
                tokenEnv = "ZOLT_CENTRAL_TOKEN"
                publishingType = "automatic"
                name = "example-library-1.0.0"
                baseUrl = "https://central.sonatype.com"
                """);
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

        assertEquals(legacy, adapted);
        assertTrue(adapted.configured());
        assertEquals(List.of("main"), adapted.artifacts());
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

    private static PublishSettings legacy(String legacySource) {
        return new PublishSettingsReader().read(legacySource, CREDENTIALS);
    }
}
