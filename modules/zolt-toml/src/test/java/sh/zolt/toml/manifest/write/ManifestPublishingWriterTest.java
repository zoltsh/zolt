package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.manifest.authored.AuthoredPublishing;

final class ManifestPublishingWriterTest {
    @Test
    void emitsEveryPublishingDomainInSchemaAndCodePointOrder() {
        AuthoredPublishing publishing = new AuthoredPublishing(
                Optional.of(new AuthoredPublicationRoutes(
                        Optional.of(id("alpha")), Optional.of(id("zeta")))),
                Map.of(
                        id("zeta"),
                        new AuthoredPublicationRepository(
                                url("https://repo.example.test/snapshots"),
                                Optional.of(id("company"))),
                        id("alpha"),
                        AuthoredPublicationRepository.unauthenticated(
                                url("https://repo.example.test/releases"))),
                Optional.of(new AuthoredPublicationSigning(
                        AuthoredPublicationSigning.Method.GPG,
                        Optional.of("release-key"),
                        Optional.of(environment("SIGNING_PASSPHRASE")))),
                Optional.of(new AuthoredCentralPublishing(
                        environment("CENTRAL_TOKEN"),
                        AuthoredCentralPublishing.Mode.AUTOMATIC,
                        Optional.of("Zolt Release"),
                        Optional.of(url("https://central.example.test/api/")))));

        String output = write(publishing);

        assertEquals(
                """
                [publish]
                release = "alpha"
                snapshot = "zeta"

                [publish.repositories.alpha]
                url = "https://repo.example.test/releases"

                [publish.repositories.zeta]
                url = "https://repo.example.test/snapshots"
                credentials = "company"

                [publish.signing]
                method = "gpg"
                keyId = "release-key"
                passphraseEnv = "SIGNING_PASSPHRASE"

                [publish.central]
                tokenEnv = "CENTRAL_TOKEN"
                mode = "automatic"
                name = "Zolt Release"
                url = "https://central.example.test/api/"
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertEquals(publishing, decodePublishing(output));
    }

    @Test
    void omitsTheImplicitCentralUrlAndNormalizesItsRoundTrip() {
        AuthoredPublishing publishing = new AuthoredPublishing(
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Optional.of(new AuthoredCentralPublishing(
                        environment("CENTRAL_TOKEN"),
                        AuthoredCentralPublishing.Mode.MANUAL,
                        Optional.empty(),
                        Optional.of(url("https://central.sonatype.com/")))));

        String output = write(publishing);

        assertEquals(
                """
                [publish.central]
                tokenEnv = "CENTRAL_TOKEN"
                mode = "manual"
                """,
                output);
        AuthoredPublishing normalized = decodePublishing(output);
        assertEquals(publishing.central().orElseThrow().tokenEnvironment(),
                normalized.central().orElseThrow().tokenEnvironment());
        assertEquals(publishing.central().orElseThrow().mode(),
                normalized.central().orElseThrow().mode());
        assertEquals(Optional.empty(), normalized.central().orElseThrow().url());
    }

    @Test
    void omitsExplicitlyEmptyPublishingSettings() {
        assertEquals("", write(AuthoredPublishing.empty()));
    }

    private static String write(AuthoredPublishing publishing) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestPublishingWriter().write(emitter, publishing);
        return emitter.finish();
    }

    private static AuthoredPublishing decodePublishing(String source) {
        return decodeAuthoredManifest("[project]\nname = \"round-trip\"\n\n" + source)
                .publishing()
                .orElseThrow();
    }

    private static EnvironmentVariableName environment(String value) {
        return new EnvironmentVariableName(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static RepositoryUrl url(String value) {
        return new RepositoryUrl(value);
    }
}
