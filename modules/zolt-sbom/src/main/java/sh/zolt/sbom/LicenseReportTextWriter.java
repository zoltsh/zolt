package sh.zolt.sbom;

import java.util.List;

/**
 * Renders a {@link LicenseReport} as a human-readable, deterministic text report grouped by license,
 * with per-dependency attribution and actionable notes for UNMAPPED and UNKNOWN groups.
 *
 * <p>With a configured license policy each reportable dependency also carries its
 * {@code [denied]}/{@code [unknown]}/{@code [exception]} status and the evaluator's evidence,
 * followed by exception audits, a policy summary, and a pointer at the enforcing command. Without
 * one the output is unchanged.
 */
public final class LicenseReportTextWriter {
    public String write(LicenseReport report) {
        return write(report, LicensePolicyAnnotations.none());
    }

    public String write(LicenseReport report, LicensePolicyAnnotations annotations) {
        StringBuilder text = new StringBuilder();
        if (report.groups().isEmpty()) {
            text.append("No dependencies in scope.\n");
            exceptionAudits(text, annotations);
            policySummary(text, annotations);
            return text.toString();
        }
        List<LicenseGroup> groups = report.groups();
        for (int index = 0; index < groups.size(); index++) {
            group(text, groups.get(index), annotations);
            if (index + 1 < groups.size()) {
                text.append('\n');
            }
        }
        exceptionAudits(text, annotations);
        policySummary(text, annotations);
        return text.toString();
    }

    private void group(StringBuilder text, LicenseGroup group, LicensePolicyAnnotations annotations) {
        text.append(heading(group)).append(" (").append(group.components().size()).append(")\n");
        for (LicenseComponentRef component : group.components()) {
            text.append("  ").append(component.coordinate());
            annotations.forDeclaration(component.coordinate(), group.label())
                    .ifPresent(finding -> finding(text, finding));
            text.append('\n');
        }
        note(group).ifPresent(note -> text.append("  note: ").append(note).append('\n'));
    }

    private static void finding(StringBuilder text, LicensePolicyFinding finding) {
        text.append("  [")
                .append(finding.verdict() == LicenseVerdict.PERMITTED_BY_EXCEPTION
                        ? "exception"
                        : LicensePolicyAnnotations.status(finding.verdict()))
                .append("] ");
        if (!finding.declaration().equals(finding.license())) {
            text.append(finding.license()).append(' ');
        }
        text.append(finding.reason());
        finding.exceptionMatch().ifPresent(match -> text.append('\n')
                .append("    reason: ")
                .append(match.reason()));
    }

    private static void exceptionAudits(StringBuilder text, LicensePolicyAnnotations annotations) {
        if (annotations.exceptionAudits().isEmpty()) {
            return;
        }
        text.append("\nLicense exceptions:\n");
        for (LicenseExceptionAudit audit : annotations.exceptionAudits()) {
            text.append("  ");
            audit.member().ifPresent(member -> text.append(member).append("  "));
            text.append(audit.exception().dependency());
            audit.exception().version().ifPresent(version -> text.append('@').append(version));
            text.append("  [").append(audit.status().jsonValue()).append(']');
            audit.resolvedVersion().filter(resolved -> audit.status() == LicenseExceptionAuditStatus.VERSION_MISMATCHED)
                    .ifPresent(resolved -> text.append(" resolved ").append(resolved));
            text.append('\n')
                    .append("    reason: ")
                    .append(audit.exception().reason())
                    .append('\n');
        }
    }

    private static void policySummary(StringBuilder text, LicensePolicyAnnotations annotations) {
        if (!annotations.configured()) {
            return;
        }
        int evaluated = annotations.evaluated();
        text.append('\n')
                .append("License policy: ")
                .append(annotations.denied())
                .append(" denied, ")
                .append(annotations.unknown())
                .append(" unknown of ")
                .append(evaluated)
                .append(evaluated == 1 ? " dependency" : " dependencies")
                .append(". ")
                .append(annotations.permittedByException())
                .append(" permitted by exception; ")
                .append(annotations.staleExceptions())
                .append(" stale ")
                .append(annotations.staleExceptions() == 1 ? "exception" : "exceptions")
                .append(".\n")
                .append("Next: run `zolt check --check license-policy` to enforce it.\n");
    }

    private static String heading(LicenseGroup group) {
        return group.url()
                .filter(url -> group.status() == SbomLicenseStatus.UNMAPPED)
                .map(url -> group.label() + " (" + url + ")")
                .orElse(group.label());
    }

    private static java.util.Optional<String> note(LicenseGroup group) {
        return switch (group.status()) {
            case UNMAPPED -> java.util.Optional.of(
                    "unrecognized license spelling; kept raw. Verify the license manually.");
            case UNKNOWN -> java.util.Optional.of(
                    "no license found in the cached POM chain; run `zolt resolve` to cache POMs, then re-run.");
            case SPDX, SPDX_EXPRESSION -> java.util.Optional.empty();
        };
    }
}
