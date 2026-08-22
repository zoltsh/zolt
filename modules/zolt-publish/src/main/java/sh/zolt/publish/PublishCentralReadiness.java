package sh.zolt.publish;

import sh.zolt.project.DeveloperEntry;
import sh.zolt.project.PublicationMetadata;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the requirements Maven Central enforces before it will accept a component: a release
 * version, the required POM metadata (name, description, url, license name+url, an identifiable
 * developer, scm url+connection), sources and Javadoc jars, GPG signatures, and checksums.
 *
 * <p>The name has no authored spelling in the final language — design §14.4 derives it from project
 * identity — so its row confirms that derivation reached the metadata the POM is written from, rather
 * than asking the author for a field they cannot write.
 *
 * <p>This is a pure function over already-resolved inputs so it can be reused by the dry-run
 * readiness report and by the Central bundle publish path.
 */
final class PublishCentralReadiness {
    private PublishCentralReadiness() {
    }

    static List<PublishCentralRequirement> evaluate(
            String versionKind,
            PublicationMetadata metadata,
            boolean sourcesJar,
            boolean javadocJar,
            boolean signaturesConfigured) {
        return evaluate(versionKind, metadata, sourcesJar, javadocJar, signaturesConfigured, false);
    }

    static List<PublishCentralRequirement> evaluate(
            String versionKind,
            PublicationMetadata metadata,
            boolean sourcesJar,
            boolean javadocJar,
            boolean signaturesConfigured,
            boolean pomPackaging) {
        return evaluate(versionKind, metadata, sourcesJar, javadocJar, signaturesConfigured, pomPackaging, false);
    }

    static List<PublishCentralRequirement> evaluate(
            String versionKind,
            PublicationMetadata metadata,
            boolean sourcesJar,
            boolean javadocJar,
            boolean signaturesConfigured,
            boolean pomPackaging,
            boolean reproducibleSigningKeyMissing) {
        List<PublishCentralRequirement> requirements = new ArrayList<>();
        requirements.add(requirement(
                "release version",
                !"snapshot".equals(versionKind),
                "Set a non-SNAPSHOT [project].version; Maven Central rejects -SNAPSHOT releases."));
        requirements.add(requirement(
                "project name",
                !metadata.name().isBlank(),
                "Set [project].name; Maven Central rejects a POM without <name>."));
        requirements.add(requirement(
                "project description",
                !metadata.description().isBlank(),
                "Add [package.metadata].description."));
        requirements.add(requirement(
                "project url",
                !metadata.url().isBlank(),
                "Add [package.metadata].url."));
        requirements.add(requirement(
                "license name and url",
                !metadata.license().isBlank() && !metadata.licenseUrl().isBlank(),
                "Set [project].license. A current SPDX identifier derives both name and url; an SPDX "
                        + "expression, an unknown identifier, or a custom license has no derivable url, "
                        + "so give the inline form explicit name and url."));
        requirements.add(requirement(
                "developer information",
                hasIdentifiableDeveloper(metadata),
                "Add [package.metadata].developers or a [package.metadata.developer.<id>] table "
                        + "with a name and email."));
        requirements.add(requirement(
                "scm url and connection",
                !metadata.scm().isBlank() && !metadata.scmConnection().isBlank(),
                "Add [package.metadata].scm and [package.metadata].scmConnection."));
        if (!pomPackaging) {
            // A BOM (pom packaging) has no compiled sources or Javadoc; Central accepts the POM alone.
            requirements.add(requirement(
                    "sources jar",
                    sourcesJar,
                    "Set [package].sources = true and re-run zolt package."));
            requirements.add(requirement(
                    "javadoc jar",
                    javadocJar,
                    "Set [package].javadoc = true and re-run zolt package."));
        }
        requirements.add(requirement(
                "gpg signatures",
                signaturesConfigured,
                "Enable [publish.signing] so every artifact and the POM are signed with a .asc file."));
        if (reproducibleSigningKeyMissing) {
            requirements.add(PublishCentralRequirement.unsatisfied(
                    "reproducible signing key",
                    "SOURCE_DATE_EPOCH is set but [publish.signing].keyId is not; pin the key so "
                            + "deterministic signing selects the same key on every machine."));
        }
        requirements.add(PublishCentralRequirement.satisfied("checksums"));
        return List.copyOf(requirements);
    }

    static boolean allSatisfied(List<PublishCentralRequirement> requirements) {
        return requirements.stream().allMatch(PublishCentralRequirement::satisfied);
    }

    private static boolean hasIdentifiableDeveloper(PublicationMetadata metadata) {
        if (!metadata.developers().isEmpty()) {
            return true;
        }
        for (DeveloperEntry developer : metadata.developerEntries()) {
            if (!developer.name().isBlank() || !developer.email().isBlank() || !developer.organization().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static PublishCentralRequirement requirement(String name, boolean satisfied, String remediation) {
        return satisfied
                ? PublishCentralRequirement.satisfied(name)
                : PublishCentralRequirement.unsatisfied(name, remediation);
    }
}
