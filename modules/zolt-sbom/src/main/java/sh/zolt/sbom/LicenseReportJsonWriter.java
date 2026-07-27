package sh.zolt.sbom;

import static sh.zolt.sbom.json.JsonWriter.indent;
import static sh.zolt.sbom.json.JsonWriter.optionalStringField;
import static sh.zolt.sbom.json.JsonWriter.rawField;
import static sh.zolt.sbom.json.JsonWriter.string;
import static sh.zolt.sbom.json.JsonWriter.stringField;

import java.util.List;
import java.util.Optional;

/**
 * Renders a {@link LicenseReport} as the Zolt-native licenses JSON (schemaVersion 1, groups view).
 *
 * <p>A configured license policy adds fields, never removes or renames them: each offending component
 * gains a {@code policy} object and the document gains a {@code licensePolicy} summary. Without a
 * policy the document is unchanged, so existing consumers keep parsing it as before.
 */
public final class LicenseReportJsonWriter {
    public String write(LicenseReport report) {
        return write(report, LicensePolicyAnnotations.none());
    }

    public String write(LicenseReport report, LicensePolicyAnnotations annotations) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        rawField(json, 1, "schemaVersion", "1", true);
        stringField(json, 1, "command", "licenses", true);
        groups(json, report.groups(), annotations);
        licensePolicy(json, annotations);
        json.append("\n}\n");
        return json.toString();
    }

    private void groups(StringBuilder json, List<LicenseGroup> groups, LicensePolicyAnnotations annotations) {
        indent(json, 1);
        string(json, "groups");
        json.append(": [");
        if (groups.isEmpty()) {
            json.append("]");
            return;
        }
        json.append('\n');
        for (int index = 0; index < groups.size(); index++) {
            group(json, groups.get(index), annotations);
            if (index + 1 < groups.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, 1).append("]");
    }

    private void group(StringBuilder json, LicenseGroup group, LicensePolicyAnnotations annotations) {
        indent(json, 2).append("{\n");
        stringField(json, 3, "license", group.label(), true);
        stringField(json, 3, "status", group.status().jsonValue(), true);
        optionalStringField(json, 3, "url", group.url(), true);
        components(json, group.components(), annotations);
        json.append('\n');
        indent(json, 2).append("}");
    }

    private void components(
            StringBuilder json,
            List<LicenseComponentRef> components,
            LicensePolicyAnnotations annotations) {
        indent(json, 3);
        string(json, "components");
        json.append(": [\n");
        for (int index = 0; index < components.size(); index++) {
            LicenseComponentRef component = components.get(index);
            indent(json, 4).append("{\n");
            stringField(json, 5, "coordinate", component.coordinate(), true);
            Optional<LicensePolicyFinding> finding = annotations.forCoordinate(component.coordinate());
            stringField(json, 5, "purl", component.purl(), finding.isPresent());
            finding.ifPresent(present -> policy(json, present));
            indent(json, 4).append("}");
            if (index + 1 < components.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, 3).append("]");
    }

    private void policy(StringBuilder json, LicensePolicyFinding finding) {
        indent(json, 5);
        string(json, "policy");
        json.append(": {\n");
        stringField(json, 6, "status", LicensePolicyAnnotations.status(finding.verdict()), true);
        stringField(json, 6, "license", finding.license(), true);
        stringField(json, 6, "reason", finding.reason(), false);
        indent(json, 5).append("}\n");
    }

    private void licensePolicy(StringBuilder json, LicensePolicyAnnotations annotations) {
        if (!annotations.configured()) {
            return;
        }
        json.append(",\n");
        indent(json, 1);
        string(json, "licensePolicy");
        json.append(": {\n");
        rawField(json, 2, "evaluated", Integer.toString(annotations.evaluated()), true);
        rawField(json, 2, "denied", Integer.toString(annotations.denied()), true);
        rawField(json, 2, "unknown", Integer.toString(annotations.unknown()), true);
        stringField(json, 2, "enforcedBy", "zolt check --check license-policy", false);
        indent(json, 1).append("}");
    }
}
