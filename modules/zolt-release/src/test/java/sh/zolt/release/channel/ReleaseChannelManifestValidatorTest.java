package sh.zolt.release.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import org.junit.jupiter.api.Test;

final class ReleaseChannelManifestValidatorTest {
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private final ReleaseChannelManifestValidator validator =
            new ReleaseChannelManifestValidator();

    @Test
    void validatesEverySupportedTargetAtExactImmutableReleaseUrls() {
        StringBuilder artifacts = new StringBuilder();
        for (ReleaseTarget target : ReleaseTarget.values()) {
            if (!artifacts.isEmpty()) {
                artifacts.append(",\n");
            }
            artifacts.append(artifact("stable", "0.1.0", target));
        }

        ReleaseChannelManifest manifest =
                validator.validate(manifest("stable", "0.1.0", artifacts.toString()));

        assertEquals(1, manifest.schemaVersion());
        assertEquals("stable", manifest.channel());
        assertEquals("0.1.0", manifest.version());
        assertEquals(COMMIT, manifest.commit());
        assertEquals(ReleaseTarget.values().length, manifest.artifacts().size());
        assertEquals("zolt.exe", manifest.artifactFor(ReleaseTarget.WINDOWS_X64).binaryName());
    }

    @Test
    void validatesPreviewAndZapVersionAndTagLayouts() {
        ReleaseChannelManifest preview = validator.validate(manifest(
                "preview",
                "0.2.0-rc.1",
                artifact("preview", "0.2.0-rc.1", ReleaseTarget.LINUX_X64)));
        ReleaseChannelManifest zap = validator.validate(manifest(
                "zap",
                "0.1.0-zap.20260803.0123456789ab",
                artifact(
                        "zap",
                        "0.1.0-zap.20260803.0123456789ab",
                        ReleaseTarget.MACOS_ARM64)));

        assertEquals("preview", preview.channel());
        assertTrue(preview.artifacts().get(0).archiveUrl().contains("/zolt-preview-v0.2.0-rc.1/"));
        assertEquals("zap", zap.channel());
        assertTrue(zap.artifacts().get(0).archiveUrl().contains("/zolt-zap-0.1.0-zap."));
    }

    @Test
    void localDevelopmentManifestMayUseFileArtifacts() {
        String archive = "zolt-0.1.0-linux-x64.tar.gz";
        ReleaseChannelManifest manifest = validator.validateLocalManifest(manifest(
                "stable",
                "0.1.0",
                artifact(
                        ReleaseTarget.LINUX_X64,
                        archive,
                        "file:///tmp/" + archive,
                        "file:///tmp/" + archive + ".sha256")));

        assertEquals("file:///tmp/" + archive, manifest.artifacts().get(0).archiveUrl());
    }

    @Test
    void rejectsUnsupportedTargetWithActionableDiagnostic() {
        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(
                        "stable",
                        "0.1.0",
                        """
                        {
                          "target": "solaris-sparc",
                          "archive": "zolt-0.1.0-solaris-sparc.tar.gz",
                          "archiveUrl": "https://github.com/zoltsh/releases/releases/download/zolt-v0.1.0/zolt-0.1.0-solaris-sparc.tar.gz",
                          "sha256": "%s",
                          "format": "tar.gz",
                          "binaryName": "zolt"
                        }
                        """.formatted("1".repeat(64)))));

        assertTrue(exception.getMessage().contains("unsupported target `solaris-sparc`"));
        assertTrue(exception.getMessage().contains("Supported targets:"));
    }

    @Test
    void missingTargetForInstallerSelectionFailsClearly() {
        ReleaseChannelManifest manifest = validator.validate(manifest(
                "stable", "0.1.0", artifact("stable", "0.1.0", ReleaseTarget.LINUX_X64)));

        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> manifest.artifactFor(ReleaseTarget.MACOS_ARM64));

        assertTrue(exception.getMessage().contains("does not include native archive target `macos-arm64`"));
    }

    @Test
    void rejectsMissingChecksumAndMalformedIntegrityFields() {
        String archive = "zolt-0.1.0-linux-x64.tar.gz";
        String origin = "https://github.com/zoltsh/releases/releases/download/zolt-v0.1.0/";
        ReleaseChannelManifestException missing = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(
                        "stable",
                        "0.1.0",
                        """
                        {
                          "target": "linux-x64",
                          "archive": "%s",
                          "archiveUrl": "%s%s",
                          "format": "tar.gz",
                          "binaryName": "zolt"
                        }
                        """.formatted(archive, origin, archive))));
        ReleaseChannelManifestException malformed = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(manifest(
                        "stable",
                        "0.1.0",
                        """
                        {
                          "target": "linux-x64",
                          "archive": "%s",
                          "archiveUrl": "%s%s",
                          "sha256": "not-a-sha",
                          "format": "tar.gz",
                          "binaryName": "zolt"
                        }
                        """.formatted(archive, origin, archive))));

        assertTrue(missing.getMessage().contains("must include checksumUrl or sha256"));
        assertTrue(malformed.getMessage().contains("exactly 64 hexadecimal"));
    }

    @Test
    void rejectsChannelVersionMismatches() {
        for (String document : new String[] {
            manifest("stable", "0.2.0-rc.1", artifact("preview", "0.2.0-rc.1", ReleaseTarget.LINUX_X64)),
            manifest("preview", "0.2.0", artifact("stable", "0.2.0", ReleaseTarget.LINUX_X64)),
            manifest("zap", "0.2.0", artifact("stable", "0.2.0", ReleaseTarget.LINUX_X64)),
            manifest("stable", "01.2.3", artifact("stable", "01.2.3", ReleaseTarget.LINUX_X64))
        }) {
            ReleaseChannelManifestException exception = assertThrows(
                    ReleaseChannelManifestException.class, () -> validator.validate(document));
            assertTrue(exception.getMessage().contains("is not valid for channel"));
        }
    }

    @Test
    void rejectsMutableOrMismatchedPublicAssetLocations() {
        String valid = manifest(
                "zap",
                "0.1.0-zap.20260803.0123456789ab",
                artifact(
                        "zap",
                        "0.1.0-zap.20260803.0123456789ab",
                        ReleaseTarget.LINUX_X64));
        for (String document : new String[] {
            valid.replace("https://github.com/zoltsh/releases", "https://dist.zolt.sh"),
            valid.replace("zolt-zap-0.1.0", "zolt-zap-other-0.1.0"),
            valid.replace("github.com/zoltsh/releases", "github.com/attacker/releases"),
            valid.replace(".tar.gz.sha256", ".tar.gz.other.sha256")
        }) {
            ReleaseChannelManifestException exception = assertThrows(
                    ReleaseChannelManifestException.class, () -> validator.validate(document));
            assertTrue(exception.getMessage().contains("immutable zoltsh/releases asset"));
        }
    }

    @Test
    void rejectsMalformedCommitTimestampAndUnsupportedChannel() {
        String valid = manifest(
                "stable", "0.1.0", artifact("stable", "0.1.0", ReleaseTarget.LINUX_X64));
        ReleaseChannelManifestException commit = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(valid.replace(COMMIT, "0123456789abcdef")));
        ReleaseChannelManifestException timestamp = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(valid.replace("2026-06-28T00:00:00Z", "yesterday")));
        ReleaseChannelManifestException offsetTimestamp = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(valid.replace(
                        "2026-06-28T00:00:00Z", "2026-06-28T01:00:00+01:00")));
        ReleaseChannelManifestException channel = assertThrows(
                ReleaseChannelManifestException.class,
                () -> validator.validate(valid.replace("\"stable\"", "\"nightly\"")));

        assertTrue(commit.getMessage().contains("40 lowercase hexadecimal"));
        assertTrue(timestamp.getMessage().contains("UTC instant"));
        assertTrue(offsetTimestamp.getMessage().contains("ending in Z"));
        assertTrue(channel.getMessage().contains("stable, preview, zap"));
    }

    private static String manifest(String channel, String version, String artifacts) {
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
                """.formatted(channel, version, COMMIT, artifacts.indent(4));
    }

    private static String artifact(String channel, String version, ReleaseTarget target) {
        String archive = "zolt-" + version + "-" + target.id() + target.archiveExtension();
        String origin = "https://github.com/zoltsh/releases/releases/download/"
                + releaseTag(channel, version)
                + "/";
        return artifact(target, archive, origin + archive, origin + archive + ".sha256");
    }

    private static String artifact(
            ReleaseTarget target,
            String archive,
            String archiveUrl,
            String checksumUrl) {
        return """
                {
                  "target": "%s",
                  "archive": "%s",
                  "archiveUrl": "%s",
                  "checksumUrl": "%s",
                  "format": "%s",
                  "binaryName": "%s"
                }
                """.formatted(
                target.id(),
                archive,
                archiveUrl,
                checksumUrl,
                target.archiveExtension().substring(1),
                target.binaryName());
    }

    private static String releaseTag(String channel, String version) {
        return switch (channel) {
            case "stable" -> "zolt-v" + version;
            case "preview" -> "zolt-preview-v" + version;
            case "zap" -> "zolt-zap-" + version;
            default -> throw new IllegalArgumentException(channel);
        };
    }
}
