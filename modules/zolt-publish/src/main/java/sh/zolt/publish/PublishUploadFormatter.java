package sh.zolt.publish;

public final class PublishUploadFormatter {
    private PublishUploadFormatter() {
    }

    public static String text(PublishUploadResult result) {
        PublishDryRunPlan plan = result.plan();
        StringBuilder output = new StringBuilder()
                .append("Zolt publish\n")
                .append("Coordinate: ").append(plan.coordinate()).append('\n')
                .append("Target repository: ").append(plan.repositoryId()).append('\n')
                .append("Artifact uploaded: ").append(plan.artifactUploadPath()).append('\n');
        for (PublishArtifactPlan artifact : plan.supplementalArtifacts()) {
            output.append("Supplemental artifact uploaded: ").append(artifact.uploadPath()).append('\n');
        }
        result.cleanupWarning().ifPresent(warning ->
                output.append("Warning: ").append(warning).append('\n'));
        return output
                .append("POM uploaded: ").append(plan.pomUploadPath()).append('\n')
                .append("Status: uploaded\n")
                .toString();
    }
}
