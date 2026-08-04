package sh.zolt.release.channel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReleaseIndexManifestValidator {
    private static final int BETA_SCHEMA_VERSION = 1;
    private static final int MAX_VERSIONS = 200;

    public ReleaseIndexManifest validate(String json) {
        return validate(json, false);
    }

    public ReleaseIndexManifest validateLocalManifest(String json) {
        return validate(json, true);
    }

    private ReleaseIndexManifest validate(String json, boolean allowFileUrls) {
        if (json == null || json.isBlank()) {
            throw new ReleaseChannelManifestException("Release index manifest is empty.");
        }
        int schemaVersion = ReleaseJsonFields.intRequired(json, "schemaVersion", "release index manifest");
        if (schemaVersion != BETA_SCHEMA_VERSION) {
            throw new ReleaseChannelManifestException(
                    "Release index manifest has unsupported schemaVersion "
                            + schemaVersion
                            + "; expected "
                            + BETA_SCHEMA_VERSION
                            + ".");
        }

        String channel = ReleaseJsonFields.stringRequired(json, "channel", "release index manifest");
        String updatedAt = ReleaseJsonFields.stringRequired(json, "updatedAt", "release index manifest");
        ReleaseChannelManifestConstraints.validateChannel(channel);
        ReleaseChannelManifestConstraints.validateCreatedAt(updatedAt);
        return new ReleaseIndexManifest(
                schemaVersion, channel, updatedAt, versions(json, channel, allowFileUrls));
    }

    private static List<ReleaseIndexVersion> versions(
            String json, String channel, boolean allowFileUrls) {
        String body = ReleaseJsonFields.arrayBody(json, "versions")
                .orElseThrow(() -> new ReleaseChannelManifestException(
                        "Release index manifest is missing versions array."));
        List<String> versionObjects = ReleaseJsonFields.objectBodies(body);
        if (versionObjects.isEmpty()) {
            throw new ReleaseChannelManifestException("Release index manifest versions array is empty.");
        }
        if (versionObjects.size() > MAX_VERSIONS) {
            throw new ReleaseChannelManifestException(
                    "Release index manifest may contain at most " + MAX_VERSIONS + " versions.");
        }

        Set<String> seenVersions = new HashSet<>();
        List<ReleaseIndexVersion> versions = new ArrayList<>();
        for (String versionJson : versionObjects) {
            ReleaseIndexVersion version = version(versionJson, channel, allowFileUrls);
            if (!seenVersions.add(version.version())) {
                throw new ReleaseChannelManifestException(
                        "Release index manifest repeats version `" + version.version() + "`.");
            }
            versions.add(version);
        }
        return versions;
    }

    private static ReleaseIndexVersion version(
            String json, String channel, boolean allowFileUrls) {
        String version = ReleaseJsonFields.stringRequired(json, "version", "release index version");
        String commit = ReleaseJsonFields.stringRequired(json, "commit", "release index version " + version);
        String createdAt = ReleaseJsonFields.stringRequired(json, "createdAt", "release index version " + version);
        ReleaseChannelManifestConstraints.validateVersion(channel, version);
        ReleaseChannelManifestConstraints.validateCommit(commit);
        ReleaseChannelManifestConstraints.validateCreatedAt(createdAt);
        List<ReleaseChannelArtifact> artifacts = ReleaseChannelManifestValidator.artifacts(json, allowFileUrls);
        if (!allowFileUrls) {
            artifacts.forEach(artifact ->
                    ReleaseChannelManifestConstraints.validateReleaseLocation(channel, version, artifact));
        }
        return new ReleaseIndexVersion(version, commit, createdAt, artifacts);
    }
}
