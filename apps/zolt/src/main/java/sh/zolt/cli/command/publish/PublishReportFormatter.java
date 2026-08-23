package sh.zolt.cli.command.publish;

import java.nio.file.Path;
import sh.zolt.publish.PublishCentralPublishOutcome;
import sh.zolt.workspace.publish.WorkspacePublishReport;

/**
 * Renders {@code zolt publish}'s human output. Split out of {@link PublishCommand} so that class
 * holds only the routing and the flow — which mode runs, and whether this directory is a workspace
 * member — rather than the shape of the text it prints.
 */
final class PublishReportFormatter {
    private PublishReportFormatter() {
    }

    static String workspaceReport(WorkspacePublishReport report) {
        StringBuilder output = new StringBuilder();
        output.append("Workspace publish family (").append(report.members().size()).append(" member(s)):\n");
        for (WorkspacePublishReport.Member member : report.members()) {
            output.append("- ").append(member.coordinate());
            if (member.bom()) {
                output.append(" [bom]");
            }
            output.append(" -> ").append(member.plan().repositoryId()).append('\n');
        }
        if (!report.blockers().isEmpty()) {
            output.append("Blockers:\n");
            for (String blocker : report.blockers()) {
                output.append("- ").append(blocker).append('\n');
            }
        }
        if (!report.notes().isEmpty()) {
            output.append("Notes:\n");
            for (String note : report.notes()) {
                output.append("- ").append(note).append('\n');
            }
        }
        report.deploymentId().ifPresent(id -> output.append("Central deployment id: ").append(id).append('\n'));
        report.centralOutcome().ifPresent(outcome -> output.append(centralStatusLine(outcome)));
        report.resumeCommand().ifPresent(command -> output.append("Resume with: ").append(command).append('\n'));
        if (report.ok()) {
            output.append(report.uploaded() ? "Uploaded the family.\n" : "No blockers. Nothing uploaded (dry run).\n");
        }
        return output.toString();
    }

    private static String centralStatusLine(PublishCentralPublishOutcome outcome) {
        return switch (outcome) {
            case UPLOADED -> "Central status: uploaded — validation continues on the Portal\n";
            case PUBLISHED -> "Central status: published to Maven Central\n";
            case AWAITING_MANUAL_RELEASE -> "Central status: validated — finish publishing in the Central Portal "
                    + "(https://central.sonatype.com/publishing/deployments)\n";
        };
    }

    static String centralProgress(PublishCentralPublishOutcome outcome) {
        return switch (outcome) {
            case UPLOADED, PUBLISHED -> "Published to Maven Central";
            case AWAITING_MANUAL_RELEASE -> "Validated on the Central Portal — release it to finish publishing";
        };
    }

    static String displayPath(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalized).toString()
                : normalized.toString();
    }
}
