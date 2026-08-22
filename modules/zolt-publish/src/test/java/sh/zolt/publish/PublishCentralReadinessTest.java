package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.DeveloperEntry;
import sh.zolt.project.PublicationMetadata;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class PublishCentralReadinessTest {
    @Test
    void reportsAllRequirementsSatisfiedForCompleteCentralMetadata() {
        PublicationMetadata metadata = new PublicationMetadata(
                "App Library",
                "A complete library.",
                "https://example.test/app",
                "Apache-2.0",
                "https://www.apache.org/licenses/LICENSE-2.0.txt",
                List.of(),
                List.of(new DeveloperEntry("ada", "Ada Lovelace", "ada@example.test", "", "")),
                "https://github.com/example/app",
                "scm:git:https://github.com/example/app.git",
                "",
                "",
                "");

        List<PublishCentralRequirement> requirements =
                PublishCentralReadiness.evaluate("release", metadata, true, true, true);

        assertTrue(PublishCentralReadiness.allSatisfied(requirements));
        assertTrue(requirements.stream().anyMatch(requirement -> requirement.name().equals("checksums")));
    }

    @Test
    void missingPinnedKeyUnderReproducibleSigningBlocksReadiness() {
        List<PublishCentralRequirement> requirements = PublishCentralReadiness.evaluate(
                "release", PublicationMetadata.empty(), true, true, true, false, true);

        PublishCentralRequirement key = requirements.stream()
                .filter(requirement -> requirement.name().equals("reproducible signing key"))
                .findFirst()
                .orElseThrow();
        assertTrue(!key.satisfied());
        assertTrue(key.remediation().contains("[publish.signing].keyId"));
    }

    @Test
    void reproducibleSigningRequirementAbsentWhenKeyPinnedOrEpochUnset() {
        List<PublishCentralRequirement> requirements = PublishCentralReadiness.evaluate(
                "release", PublicationMetadata.empty(), true, true, true, false, false);

        assertTrue(requirements.stream().noneMatch(
                requirement -> requirement.name().equals("reproducible signing key")));
    }

    @Test
    void pomPackagingOmitsSourcesAndJavadocButKeepsSigningAndChecksums() {
        PublicationMetadata metadata = new PublicationMetadata(
                "Acme Platform BOM",
                "Curated platform versions.",
                "https://example.test/bom",
                "Apache-2.0",
                "https://www.apache.org/licenses/LICENSE-2.0.txt",
                List.of(),
                List.of(new DeveloperEntry("ada", "Ada Lovelace", "ada@example.test", "", "")),
                "https://github.com/example/bom",
                "scm:git:https://github.com/example/bom.git",
                "",
                "",
                "");

        // A BOM has no sources/javadoc jars; pass false for both and still expect readiness.
        List<PublishCentralRequirement> requirements =
                PublishCentralReadiness.evaluate("release", metadata, false, false, true, true);

        Map<String, PublishCentralRequirement> byName = requirements.stream()
                .collect(Collectors.toMap(PublishCentralRequirement::name, Function.identity()));
        assertFalse(byName.containsKey("sources jar"));
        assertFalse(byName.containsKey("javadoc jar"));
        assertTrue(byName.get("gpg signatures").satisfied());
        assertTrue(byName.get("checksums").satisfied());
        assertTrue(PublishCentralReadiness.allSatisfied(requirements));
    }

    @Test
    void flagsMissingRequirementsWithActionableRemediation() {
        List<PublishCentralRequirement> requirements =
                PublishCentralReadiness.evaluate("snapshot", PublicationMetadata.empty(), false, false, false);

        assertFalse(PublishCentralReadiness.allSatisfied(requirements));
        Map<String, PublishCentralRequirement> byName = requirements.stream()
                .collect(Collectors.toMap(PublishCentralRequirement::name, Function.identity()));

        assertFalse(byName.get("release version").satisfied());
        assertTrue(byName.get("release version").remediation().contains("-SNAPSHOT"));
        assertFalse(byName.get("project name").satisfied());
        assertTrue(byName.get("project name").remediation().contains("[project].name"));
        assertFalse(byName.get("license name and url").satisfied());
        assertTrue(byName.get("license name and url").remediation().contains("[project].license"));
        assertFalse(byName.get("developer information").satisfied());
        assertFalse(byName.get("scm url and connection").satisfied());
        assertFalse(byName.get("sources jar").satisfied());
        assertFalse(byName.get("javadoc jar").satisfied());
        assertFalse(byName.get("gpg signatures").satisfied());
        // Checksums are always uploaded, so that requirement is met even for an otherwise-bare project.
        assertTrue(byName.get("checksums").satisfied());
    }

    @Test
    void plainDeveloperNameCountsAsIdentifiableInformation() {
        PublicationMetadata metadata = new PublicationMetadata(
                "App", "", "", "", "", List.of("Ada Lovelace"), List.of(), "", "", "", "", "");

        List<PublishCentralRequirement> requirements =
                PublishCentralReadiness.evaluate("release", metadata, false, false, false);

        assertTrue(requirements.stream()
                .filter(requirement -> requirement.name().equals("developer information"))
                .allMatch(PublishCentralRequirement::satisfied));
    }

    @Test
    void formatterRendersCheckboxesAndStatus() {
        List<PublishCentralRequirement> requirements = List.of(
                PublishCentralRequirement.satisfied("project name"),
                PublishCentralRequirement.unsatisfied("gpg signatures", "Enable [publish.signing]."));

        String text = PublishDryRunFormatter.centralReadiness(requirements);

        assertEquals("""
                Maven Central readiness:
                - [x] project name
                - [ ] gpg signatures
                      Next: Enable [publish.signing].
                Central status: not ready
                """, text);
    }
}
