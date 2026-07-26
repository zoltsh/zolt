package sh.zolt.build.generatedsource;

import sh.zolt.project.GeneratedSourceKind;

/**
 * Canonical producer identity for one generated-source declaration.
 */
public record GeneratedSourceProducerFingerprint(
        String scope,
        String stepId,
        GeneratedSourceKind kind,
        String fingerprint) {
    public GeneratedSourceProducerFingerprint {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("Generated source fingerprint scope is required.");
        }
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("Generated source fingerprint step id is required.");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Generated source fingerprint kind is required.");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("Generated source fingerprint is required.");
        }
    }
}
