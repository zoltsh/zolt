package sh.zolt.publish;

import java.util.Optional;

public record PublishUploadResult(
        PublishDryRunPlan plan,
        Optional<String> cleanupWarning) {
    public PublishUploadResult(PublishDryRunPlan plan) {
        this(plan, Optional.empty());
    }

    public PublishUploadResult {
        if (plan == null) {
            throw new PublishException("Publish upload result requires a plan.");
        }
        cleanupWarning =
                cleanupWarning == null ? Optional.empty() : cleanupWarning;
    }
}
