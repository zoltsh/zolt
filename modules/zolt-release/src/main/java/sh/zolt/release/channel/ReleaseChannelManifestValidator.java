package sh.zolt.release.channel;

import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.archive.ReleaseArchiveException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ReleaseChannelManifestValidator {
    private static final int BETA_SCHEMA_VERSION = 1;

    public ReleaseChannelManifest validate(String json) {
        return validate(json, false);
    }

    public ReleaseChannelManifest validateLocalManifest(String json) {
        return validate(json, true);
    }

    private ReleaseChannelManifest validate(String json, boolean allowFileUrls) {
        if (json == null || json.isBlank()) {
            throw new ReleaseChannelManifestException("Release channel manifest is empty.");
        }
        int schemaVersion = ReleaseJsonFields.intRequired(json, "schemaVersion", "release channel manifest");
        if (schemaVersion != BETA_SCHEMA_VERSION) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest has unsupported schemaVersion "
                            + schemaVersion
                            + "; expected "
                            + BETA_SCHEMA_VERSION
                            + ".");
        }

        String channel = ReleaseJsonFields.stringRequired(json, "channel", "release channel manifest");
        String version = ReleaseJsonFields.stringRequired(json, "version", "release channel manifest");
        String commit = ReleaseJsonFields.stringRequired(json, "commit", "release channel manifest");
        String createdAt = ReleaseJsonFields.stringRequired(json, "createdAt", "release channel manifest");
        ReleaseChannelManifestConstraints.validateChannel(channel);
        ReleaseChannelManifestConstraints.validateVersion(channel, version);
        ReleaseChannelManifestConstraints.validateCommit(commit);
        ReleaseChannelManifestConstraints.validateCreatedAt(createdAt);
        List<ReleaseChannelArtifact> artifacts = artifacts(json, allowFileUrls);
        if (!allowFileUrls) {
            artifacts.forEach(artifact ->
                    ReleaseChannelManifestConstraints.validateReleaseLocation(channel, version, artifact));
        }
        return new ReleaseChannelManifest(schemaVersion, channel, version, commit, createdAt, artifacts);
    }

    static List<ReleaseChannelArtifact> artifacts(String json, boolean allowFileUrls) {
        String body = ReleaseJsonFields.arrayBody(json, "artifacts")
                .orElseThrow(() -> new ReleaseChannelManifestException(
                        "Release channel manifest is missing artifacts array."));
        List<String> artifactObjects = ReleaseJsonFields.objectBodies(body);
        if (artifactObjects.isEmpty()) {
            throw new ReleaseChannelManifestException("Release channel manifest artifacts array is empty.");
        }

        Set<ReleaseTarget> seenTargets = new HashSet<>();
        List<ReleaseChannelArtifact> artifacts = new ArrayList<>();
        for (String artifactJson : artifactObjects) {
            ReleaseChannelArtifact artifact = artifact(artifactJson, allowFileUrls);
            if (!seenTargets.add(artifact.target())) {
                throw new ReleaseChannelManifestException(
                        "Release channel manifest repeats target `" + artifact.target().id() + "`.");
            }
            artifacts.add(artifact);
        }
        return artifacts;
    }

    static ReleaseChannelArtifact artifact(String json, boolean allowFileUrls) {
        String targetId = ReleaseJsonFields.stringRequired(json, "target", "release channel artifact");
        ReleaseTarget target;
        try {
            target = ReleaseTarget.fromId(targetId);
        } catch (ReleaseArchiveException exception) {
            throw new ReleaseChannelManifestException(
                    "Release channel manifest has unsupported target `"
                            + targetId
                            + "`. Supported targets: "
                            + ReleaseTarget.supportedTargets()
                            + ".");
        }

        String archive = ReleaseJsonFields.stringRequired(json, "archive", "release channel artifact " + target.id());
        String archiveUrl = ReleaseJsonFields.stringRequired(json, "archiveUrl", "release channel artifact " + target.id());
        Optional<String> checksumUrl = ReleaseJsonFields.string(json, "checksumUrl");
        Optional<String> sha256 = ReleaseJsonFields.string(json, "sha256");
        String format = ReleaseJsonFields.stringRequired(json, "format", "release channel artifact " + target.id());
        String binaryName = ReleaseJsonFields.stringRequired(json, "binaryName", "release channel artifact " + target.id());
        Optional<ReleaseChannelArtifact.Signature> signature = signature(json);

        validateArtifact(target, archive, archiveUrl, checksumUrl, sha256, format, binaryName, signature, allowFileUrls);
        return new ReleaseChannelArtifact(
                target,
                archive,
                archiveUrl,
                checksumUrl,
                sha256,
                format,
                binaryName,
                signature);
    }

    private static void validateArtifact(
            ReleaseTarget target,
            String archive,
            String archiveUrl,
            Optional<String> checksumUrl,
            Optional<String> sha256,
            String format,
            String binaryName,
            Optional<ReleaseChannelArtifact.Signature> signature,
            boolean allowFileUrls) {
        String expectedFormat = target.archiveExtension().substring(1);
        if (!format.equals(expectedFormat)) {
            throw new ReleaseChannelManifestException(
                    "Release channel artifact `"
                            + target.id()
                            + "` has format `"
                            + format
                            + "`; expected `"
                            + expectedFormat
                            + "`.");
        }
        if (!binaryName.equals(target.binaryName())) {
            throw new ReleaseChannelManifestException(
                    "Release channel artifact `"
                            + target.id()
                            + "` has binaryName `"
                            + binaryName
                            + "`; expected `"
                            + target.binaryName()
                            + "`.");
        }
        ReleaseChannelManifestConstraints.validateArchiveFilename(target, archive);
        ReleaseChannelManifestConstraints.validateUrl("archiveUrl", archiveUrl, allowFileUrls);
        if (!archive.endsWith(target.archiveExtension()) || !archiveUrl.endsWith(target.archiveExtension())) {
            throw new ReleaseChannelManifestException(
                    "Release channel artifact `"
                            + target.id()
                            + "` must reference a native "
                            + expectedFormat
                            + " archive, not a JVM/JRE artifact.");
        }
        checksumUrl.ifPresent(value -> {
            ReleaseChannelManifestConstraints.validateUrl("checksumUrl", value, allowFileUrls);
            if (!value.endsWith(".sha256")) {
                throw new ReleaseChannelManifestException(
                        "Release channel artifact `" + target.id() + "` checksumUrl must reference a .sha256 sidecar.");
            }
        });
        sha256.ifPresent(value -> ReleaseChannelManifestConstraints.validateSha256(target, value));
        signature.ifPresent(value -> ReleaseChannelManifestConstraints.validateSignature(value, allowFileUrls));
        if (checksumUrl.isEmpty() && sha256.isEmpty()) {
            throw new ReleaseChannelManifestException(
                    "Release channel artifact `" + target.id() + "` must include checksumUrl or sha256.");
        }
    }

    private static Optional<ReleaseChannelArtifact.Signature> signature(String json) {
        Optional<String> body = ReleaseJsonFields.objectBody(json, "signature");
        if (body.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReleaseChannelArtifact.Signature(
                ReleaseJsonFields.stringRequired(body.orElseThrow(), "kind", "release channel signature"),
                ReleaseJsonFields.stringRequired(body.orElseThrow(), "url", "release channel signature")));
    }
}
