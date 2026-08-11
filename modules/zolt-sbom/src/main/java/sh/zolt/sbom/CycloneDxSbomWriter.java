package sh.zolt.sbom;

import static sh.zolt.sbom.json.JsonWriter.indent;
import static sh.zolt.sbom.json.JsonWriter.rawField;
import static sh.zolt.sbom.json.JsonWriter.string;
import static sh.zolt.sbom.json.JsonWriter.stringField;

import java.util.List;
import sh.zolt.sbom.json.JsonWriter;

/**
 * Renders an {@link SbomModel} as CycloneDX 1.5 JSON.
 *
 * <p>The subset is fixed in code: {@code bomFormat}, {@code specVersion}, {@code serialNumber},
 * {@code version}, {@code metadata{timestamp?, tools[], component}}, {@code components[]} and
 * {@code dependencies[]}. Every collection arrives already sorted, so the bytes are a pure function
 * of the model. Golden-file byte-equality tests enforce this.
 */
public final class CycloneDxSbomWriter {
    public String write(SbomModel model) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        stringField(json, 1, "bomFormat", "CycloneDX", true);
        stringField(json, 1, "specVersion", "1.5", true);
        stringField(json, 1, "serialNumber", model.serialNumber(), true);
        rawField(json, 1, "version", "1", true);
        metadata(json, model);
        json.append(",\n");
        componentsArray(json, model.components());
        json.append(",\n");
        dependenciesArray(json, model.dependencies());
        json.append("\n}\n");
        return json.toString();
    }

    private void metadata(StringBuilder json, SbomModel model) {
        indent(json, 1);
        string(json, "metadata");
        json.append(": {\n");
        model.timestamp().ifPresent(timestamp -> stringField(json, 2, "timestamp", timestamp, true));
        toolsArray(json, 2, model.tools());
        json.append(",\n");
        indent(json, 2);
        string(json, "component");
        json.append(": ");
        component(json, 2, model.metadataComponent(), false);
        json.append('\n');
        indent(json, 1).append("}");
    }

    private void toolsArray(StringBuilder json, int level, List<SbomTool> tools) {
        indent(json, level);
        string(json, "tools");
        json.append(": [");
        if (tools.isEmpty()) {
            json.append("]");
            return;
        }
        json.append('\n');
        for (int index = 0; index < tools.size(); index++) {
            SbomTool tool = tools.get(index);
            indent(json, level + 1).append("{\n");
            stringField(json, level + 2, "name", tool.name(), true);
            stringField(json, level + 2, "version", tool.version(), false);
            indent(json, level + 1).append("}");
            if (index + 1 < tools.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, level).append("]");
    }

    private void componentsArray(StringBuilder json, List<SbomComponent> components) {
        indent(json, 1);
        string(json, "components");
        json.append(": [");
        if (components.isEmpty()) {
            json.append("]");
            return;
        }
        json.append('\n');
        for (int index = 0; index < components.size(); index++) {
            indent(json, 2);
            component(json, 2, components.get(index), true);
            if (index + 1 < components.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, 1).append("]");
    }

    private void component(StringBuilder json, int level, SbomComponent component, boolean includeScope) {
        boolean hasGroup = !component.group().isBlank();
        boolean hasVersion = !component.version().isBlank();
        boolean hasPurl = !component.purl().isBlank();
        boolean hasScope = includeScope;
        boolean hasHashes = !component.hashes().isEmpty();
        boolean hasLicenses = !component.licenses().isEmpty();
        json.append("{\n");
        stringField(json, level + 1, "type", component.type().jsonValue(), true);
        stringField(json, level + 1, "bom-ref", component.bomRef(), true);
        if (hasGroup) {
            stringField(json, level + 1, "group", component.group(), true);
        }
        stringField(json, level + 1, "name", component.name(),
                hasVersion || hasPurl || hasScope || hasHashes || hasLicenses);
        if (hasVersion) {
            stringField(json, level + 1, "version", component.version(),
                    hasPurl || hasScope || hasHashes || hasLicenses);
        }
        if (hasPurl) {
            stringField(json, level + 1, "purl", component.purl(), hasScope || hasHashes || hasLicenses);
        }
        if (hasScope) {
            stringField(json, level + 1, "scope", component.scope().jsonValue(), hasHashes || hasLicenses);
        }
        if (hasHashes) {
            hashesArray(json, level + 1, component.hashes());
            json.append(hasLicenses ? ",\n" : "\n");
        }
        if (hasLicenses) {
            licensesArray(json, level + 1, component.licenses());
            json.append('\n');
        }
        indent(json, level).append("}");
    }

    private void licensesArray(StringBuilder json, int level, List<SbomLicense> licenses) {
        indent(json, level);
        string(json, "licenses");
        json.append(": [\n");
        if (licenses.size() == 1 && licenses.getFirst().status() == SbomLicenseStatus.SPDX_EXPRESSION) {
            expressionLicense(json, level + 1, licenses.getFirst());
            json.append('\n');
            indent(json, level).append("]");
            return;
        }
        for (int index = 0; index < licenses.size(); index++) {
            SbomLicense license = licenses.get(index);
            indent(json, level + 1).append("{\n");
            indent(json, level + 2);
            string(json, "license");
            json.append(": {\n");
            if (license.status() == SbomLicenseStatus.SPDX) {
                stringField(json, level + 3, "id", license.spdxId().orElseThrow(), false);
            } else {
                boolean hasUrl = license.url().isPresent();
                stringField(json, level + 3, "name", license.displayName(), hasUrl);
                if (hasUrl) {
                    stringField(json, level + 3, "url", license.url().orElseThrow(), false);
                }
            }
            indent(json, level + 2).append("}\n");
            indent(json, level + 1).append("}");
            if (index + 1 < licenses.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, level).append("]");
    }

    private void expressionLicense(StringBuilder json, int level, SbomLicense license) {
        indent(json, level).append("{\n");
        stringField(
                json,
                level + 1,
                "expression",
                license.expression().orElseThrow().canonical(),
                false);
        indent(json, level).append("}");
    }

    private void hashesArray(StringBuilder json, int level, List<SbomHash> hashes) {
        indent(json, level);
        string(json, "hashes");
        json.append(": [\n");
        for (int index = 0; index < hashes.size(); index++) {
            SbomHash hash = hashes.get(index);
            indent(json, level + 1).append("{\n");
            stringField(json, level + 2, "alg", hash.alg(), true);
            stringField(json, level + 2, "content", hash.content(), false);
            indent(json, level + 1).append("}");
            if (index + 1 < hashes.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, level).append("]");
    }

    private void dependenciesArray(StringBuilder json, List<SbomDependency> dependencies) {
        indent(json, 1);
        string(json, "dependencies");
        json.append(": [");
        if (dependencies.isEmpty()) {
            json.append("]");
            return;
        }
        json.append('\n');
        for (int index = 0; index < dependencies.size(); index++) {
            SbomDependency dependency = dependencies.get(index);
            indent(json, 2).append("{\n");
            stringField(json, 3, "ref", dependency.ref(), true);
            dependsOnArray(json, 3, dependency.dependsOn());
            json.append('\n');
            indent(json, 2).append("}");
            if (index + 1 < dependencies.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, 1).append("]");
    }

    private void dependsOnArray(StringBuilder json, int level, List<String> dependsOn) {
        indent(json, level);
        string(json, "dependsOn");
        json.append(": [");
        for (int index = 0; index < dependsOn.size(); index++) {
            if (index > 0) {
                json.append(", ");
            }
            JsonWriter.string(json, dependsOn.get(index));
        }
        json.append("]");
    }
}
