package sh.zolt.toml.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable lookup and canonical-order view of manifest schema metadata. */
public final class ManifestSchemaRegistry {
    private static final Comparator<ManifestField> FIELD_ORDER = Comparator
            .comparingInt(ManifestField::canonicalOrder)
            .thenComparing(ManifestField::path);
    private static final Comparator<ManifestSection> SECTION_ORDER = Comparator
            .comparingInt(ManifestSection::canonicalOrder)
            .thenComparing(ManifestSection::path);

    private final List<ManifestField> fields;
    private final List<ManifestSection> sections;
    private final Map<ManifestPath, ManifestField> fieldsByPath;
    private final Map<ManifestPath, ManifestSection> sectionsByPath;

    public ManifestSchemaRegistry(
            Collection<ManifestField> fields,
            Collection<ManifestSection> sections) {
        Objects.requireNonNull(fields, "Manifest fields are required.");
        Objects.requireNonNull(sections, "Manifest sections are required.");

        LinkedHashMap<ManifestPath, ManifestField> mutableFields = new LinkedHashMap<>();
        for (ManifestField field : fields) {
            ManifestField value = Objects.requireNonNull(field, "Manifest fields must not contain null.");
            if (mutableFields.putIfAbsent(value.path(), value) != null) {
                throw duplicate("field", value.path());
            }
        }

        LinkedHashMap<ManifestPath, ManifestSection> mutableSections = new LinkedHashMap<>();
        for (ManifestSection section : sections) {
            ManifestSection value = Objects.requireNonNull(section, "Manifest sections must not contain null.");
            if (mutableSections.putIfAbsent(value.path(), value) != null) {
                throw duplicate("section", value.path());
            }
        }

        ArrayList<ManifestField> orderedFields = new ArrayList<>(mutableFields.values());
        orderedFields.sort(FIELD_ORDER);
        this.fields = List.copyOf(orderedFields);
        ArrayList<ManifestSection> orderedSections = new ArrayList<>(mutableSections.values());
        orderedSections.sort(SECTION_ORDER);
        this.sections = List.copyOf(orderedSections);
        this.fieldsByPath = Map.copyOf(mutableFields);
        this.sectionsByPath = Map.copyOf(mutableSections);
    }

    public List<ManifestField> fields() {
        return fields;
    }

    public List<ManifestSection> sections() {
        return sections;
    }

    public Optional<ManifestField> field(ManifestPath path) {
        return Optional.ofNullable(fieldsByPath.get(Objects.requireNonNull(path, "Manifest field path is required.")));
    }

    public Optional<ManifestSection> section(ManifestPath path) {
        return Optional.ofNullable(
                sectionsByPath.get(Objects.requireNonNull(path, "Manifest section path is required.")));
    }

    private static IllegalArgumentException duplicate(String kind, ManifestPath path) {
        return new IllegalArgumentException("Duplicate manifest " + kind + " path `" + path + "`.");
    }
}
