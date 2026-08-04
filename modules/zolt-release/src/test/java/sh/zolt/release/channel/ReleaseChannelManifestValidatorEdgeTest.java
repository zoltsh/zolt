package sh.zolt.release.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import org.junit.jupiter.api.Test;

final class ReleaseChannelManifestValidatorEdgeTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String ARCHIVE = "zolt-0.1.0-linux-x64.tar.gz";
    private static final String ORIGIN =
            "https://github.com/zoltsh/releases/releases/download/zolt-v0.1.0/";
    private final ReleaseChannelManifestValidator validator =
            new ReleaseChannelManifestValidator();

    @Test
    void acceptsFileUrlsOnlyForExplicitLocalValidation() {
        String json = manifest(artifact(
                "file:///tmp/" + ARCHIVE,
                "\"checksumUrl\": \"file:///tmp/" + ARCHIVE + ".sha256\","));

        ReleaseChannelManifest local = validator.validateLocalManifest(json);
        assertEquals(
                "file:///tmp/" + ARCHIVE,
                local.artifactFor(ReleaseTarget.LINUX_X64).archiveUrl());

        ReleaseChannelManifestException publicFailure = assertThrows(
                ReleaseChannelManifestException.class, () -> validator.validate(json));
        assertTrue(publicFailure.getMessage().contains("HTTPS"));
    }

    @Test
    void rejectsMissingEmptyAndDuplicateArtifactArrays() {
        ReleaseChannelManifestException missing = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(header() + "}"));
        ReleaseChannelManifestException empty = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(header() + "\"artifacts\": []}"));
        ReleaseChannelManifestException duplicate = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(artifact(), artifact())));

        assertEquals("Release channel manifest is missing artifacts array.", missing.getMessage());
        assertEquals("Release channel manifest artifacts array is empty.", empty.getMessage());
        assertEquals("Release channel manifest repeats target `linux-x64`.", duplicate.getMessage());
    }

    @Test
    void rejectsCredentialedAndMalformedUrlsBeforeOriginPinning() {
        ReleaseChannelManifestException credentials = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(artifact(
                        "https://user:secret@github.com/zoltsh/releases/releases/download/zolt-v0.1.0/"
                                + ARCHIVE,
                        "\"sha256\": \"" + "a".repeat(64) + "\","))));
        ReleaseChannelManifestException missingHost = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(artifact(
                        "https:///" + ARCHIVE,
                        "\"sha256\": \"" + "a".repeat(64) + "\","))));

        assertTrue(credentials.getMessage().contains("must not include URL credentials"));
        assertTrue(missingHost.getMessage().contains("valid HTTPS URL"));
    }

    @Test
    void rejectsChecksumSidecarsWithTheWrongSuffix() {
        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(artifact(
                        ORIGIN + ARCHIVE,
                        "\"checksumUrl\": \"" + ORIGIN + ARCHIVE + ".sig\","))));

        assertTrue(exception.getMessage().contains("must reference a .sha256 sidecar"));
    }

    @Test
    void rejectsArtifactUrlsWithQueriesOrFragments() {
        ReleaseChannelManifestException query = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(artifact(
                        ORIGIN + ARCHIVE,
                        "\"checksumUrl\": \"" + ORIGIN + ARCHIVE + ".sha256?download=1\","))));

        assertTrue(query.getMessage().contains("must not include a query or fragment"));
    }

    @Test
    void rejectsFormatAndBinaryNameThatDoNotMatchTarget() {
        String windowsArchive = "zolt-0.1.0-windows-x64.zip";
        String windows = """
                {
                  "target": "windows-x64",
                  "archive": "%s",
                  "archiveUrl": "%s%s",
                  "sha256": "%s",
                  "format": "%s",
                  "binaryName": "%s"
                }
                """;
        ReleaseChannelManifestException format = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(windows.formatted(
                        windowsArchive,
                        ORIGIN,
                        windowsArchive,
                        "a".repeat(64),
                        "tar.gz",
                        "zolt.exe"))));
        ReleaseChannelManifestException binary = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(windows.formatted(
                        windowsArchive,
                        ORIGIN,
                        windowsArchive,
                        "a".repeat(64),
                        "zip",
                        "zolt"))));

        assertTrue(format.getMessage().contains("expected `zip`"));
        assertTrue(binary.getMessage().contains("expected `zolt.exe`"));
    }

    @Test
    void rejectsEmptyMissingAndUnsupportedSchemaVersions() {
        assertEquals(
                "Release channel manifest is empty.",
                assertThrows(
                                ReleaseChannelManifestException.class,
                                () -> validator.validate(null))
                        .getMessage());
        assertEquals(
                "release channel manifest is missing `schemaVersion`.",
                assertThrows(
                                ReleaseChannelManifestException.class,
                                () -> validator.validate("{\"channel\":\"stable\"}"))
                        .getMessage());
        assertEquals(
                "Release channel manifest has unsupported schemaVersion 2; expected 1.",
                assertThrows(
                                ReleaseChannelManifestException.class,
                                () -> validator.validate("{\"schemaVersion\":2}"))
                        .getMessage());
    }

    @Test
    void parsesEscapedOfficialUrls() {
        String escaped = (ORIGIN + ARCHIVE).replace("/", "\\/");
        ReleaseChannelManifest manifest = validator.validate(manifest(artifact(
                escaped,
                "\"sha256\": \"" + "a".repeat(64) + "\",")));

        assertEquals(ORIGIN + ARCHIVE, manifest.artifacts().get(0).archiveUrl());
    }

    @Test
    void rejectsUnsafeVersionArchiveAndSignatureFields() {
        ReleaseChannelManifestException version = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifestFor("stable", " 0.1.0", artifact())));
        ReleaseChannelManifestException archive = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(artifact().replace(ARCHIVE, "../" + ARCHIVE))));
        String signed = artifact().replace(
                "\"format\"",
                "\"signature\": {\"kind\": \"mini/sign\", \"url\": \""
                        + ORIGIN
                        + ARCHIVE
                        + ".minisig\"},\n  \"format\"");
        ReleaseChannelManifestException signature = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(signed)));

        assertTrue(version.getMessage().contains("safe path segment"));
        assertTrue(archive.getMessage().contains("archive"));
        assertTrue(signature.getMessage().contains("signature kind"));
    }

    private static String header() {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "stable",
                  "version": "0.1.0",
                  "commit": "%s",
                  "createdAt": "2026-06-28T00:00:00Z",
                """.formatted(COMMIT);
    }

    private static String manifest(String... artifacts) {
        return manifestFor("stable", "0.1.0", artifacts);
    }

    private static String manifestFor(String channel, String version, String... artifacts) {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "%s",
                  "version": "%s",
                  "commit": "%s",
                  "createdAt": "2026-06-28T00:00:00Z",
                  "artifacts": [
                %s
                  ]
                }
                """.formatted(channel, version, COMMIT, String.join(",\n", artifacts).indent(4));
    }

    private static String artifact() {
        return artifact(
                ORIGIN + ARCHIVE,
                "\"checksumUrl\": \"" + ORIGIN + ARCHIVE + ".sha256\",");
    }

    private static String artifact(String archiveUrl, String checksumOrSha) {
        return """
                {
                  "target": "linux-x64",
                  "archive": "%s",
                  "archiveUrl": "%s",
                  %s
                  "format": "tar.gz",
                  "binaryName": "zolt"
                }
                """.formatted(ARCHIVE, archiveUrl, checksumOrSha);
    }
}
