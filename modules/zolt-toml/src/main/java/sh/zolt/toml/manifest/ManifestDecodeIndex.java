package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;
import sh.zolt.toml.schema.SectionKind;

/** Identity-keyed access to source-ordered fields and authored section evidence. */
final class ManifestDecodeIndex {
    private final Map<ManifestField, List<ValidatedManifestField>> fields;
    private final Map<ManifestPath, List<ValidatedManifestSection>> sections;

    ManifestDecodeIndex(ValidatedManifestShape shape) {
        Objects.requireNonNull(shape, "Validated manifest shape is required.");
        this.fields = indexFields(shape.fields());
        this.sections = indexSections(shape.sections());
    }

    Optional<ValidatedManifestField> field(ManifestField handle) {
        ManifestField registered = requireFieldHandle(handle);
        requirePlaceholderCount(registered, 0, "field");
        List<ValidatedManifestField> matches = fields.getOrDefault(registered, List.of());
        if (matches.size() > 1) {
            throw duplicateField(registered.path());
        }
        return matches.stream().findFirst();
    }

    List<Entry> entries(ManifestField handle) {
        ManifestField registered = requireFieldHandle(handle);
        requirePlaceholderCount(registered, 1, "entry");
        String bindingName = registered.path().placeholderNames().getFirst();
        ArrayList<Entry> entries = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (ValidatedManifestField field : fields.getOrDefault(registered, List.of())) {
            String key = field.schema().bindings().get(bindingName);
            if (key == null) {
                throw new IllegalStateException(
                        "Validated manifest entry `" + field.path()
                                + "` is missing schema binding `" + bindingName + "`.");
            }
            if (!keys.add(key)) {
                throw new IllegalStateException(
                        "Duplicate validated manifest entry `" + field.path() + "`.");
            }
            entries.add(new Entry(key, field));
        }
        return List.copyOf(entries);
    }

    Optional<ValidatedManifestSection> section(ManifestPath handle) {
        ManifestSection registered = ManifestSchemaEvidence.sectionHandle(handle);
        requirePlaceholderCount(registered.path(), 0, "section");
        List<ValidatedManifestSection> matches =
                sections.getOrDefault(registered.path(), List.of());
        if (matches.size() > 1) {
            throw duplicateSection(registered.path());
        }
        return matches.stream().findFirst();
    }

    List<SectionEntry> sectionEntries(ManifestPath handle) {
        ManifestSection registered = ManifestSchemaEvidence.sectionHandle(handle);
        requirePlaceholderCount(registered.path(), 1, "section entry");
        if (registered.kind() != SectionKind.NAMED_ITEM) {
            throw new IllegalArgumentException(
                    "Manifest section-entry access requires a named-item section path.");
        }
        String bindingName = registered.path().placeholderNames().getFirst();
        ArrayList<SectionEntry> entries = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (ValidatedManifestSection section :
                sections.getOrDefault(registered.path(), List.of())) {
            String key = section.schema().orElseThrow().bindings().get(bindingName);
            if (key == null) {
                throw new IllegalStateException(
                        "Validated manifest section `" + section.path()
                                + "` is missing schema binding `" + bindingName + "`.");
            }
            if (!keys.add(key)) {
                throw new IllegalStateException(
                        "Duplicate validated manifest section `[" + section.path() + "]`.");
            }
            entries.add(new SectionEntry(key, section));
        }
        return List.copyOf(entries);
    }

    Optional<ValidatedManifestField> field(SectionEntry parent, ManifestField handle) {
        ManifestField registeredField = requireFieldHandle(handle);
        requirePlaceholderCount(registeredField.path(), 1, "child field");
        ManifestSection registeredSection = requireOwnedSectionEntry(parent);
        requireDirectChild(registeredSection, registeredField);
        ManifestPath concretePath = parent.section().path()
                .child(registeredField.path().segments().getLast());
        return fields.getOrDefault(registeredField, List.of()).stream()
                .filter(field -> field.path().equals(concretePath))
                .findFirst();
    }

    private Map<ManifestField, List<ValidatedManifestField>> indexFields(
            List<ValidatedManifestField> validatedFields) {
        IdentityHashMap<ManifestField, ArrayList<ValidatedManifestField>> mutable =
                new IdentityHashMap<>();
        for (ValidatedManifestField field : validatedFields) {
            ManifestField descriptor = ManifestSchemaEvidence.validatedField(field);
            ArrayList<ValidatedManifestField> matches =
                    mutable.computeIfAbsent(descriptor, ignored -> new ArrayList<>());
            if (descriptor.path().placeholderNames().isEmpty() && !matches.isEmpty()) {
                throw duplicateField(descriptor.path());
            }
            if (descriptor.path().placeholderNames().size() == 1) {
                String binding = descriptor.path().placeholderNames().getFirst();
                String key = field.schema().bindings().get(binding);
                boolean duplicate = matches.stream().anyMatch(candidate ->
                        Objects.equals(candidate.schema().bindings().get(binding), key));
                if (duplicate) {
                    throw new IllegalStateException(
                            "Duplicate validated manifest entry `" + field.path() + "`.");
                }
            }
            matches.add(field);
        }
        IdentityHashMap<ManifestField, List<ValidatedManifestField>> immutable =
                new IdentityHashMap<>();
        mutable.forEach((handle, values) -> immutable.put(handle, List.copyOf(values)));
        return Collections.unmodifiableMap(immutable);
    }

    private Map<ManifestPath, List<ValidatedManifestSection>> indexSections(
            List<ValidatedManifestSection> validatedSections) {
        IdentityHashMap<ManifestPath, ArrayList<ValidatedManifestSection>> mutable =
                new IdentityHashMap<>();
        for (ValidatedManifestSection section : validatedSections) {
            if (section.schema().isEmpty()) {
                if (ManifestSchemaEvidence.hasRegisteredSection(section.path())) {
                    throw new IllegalStateException(
                            "Validated manifest section `" + section.path()
                                    + "` is missing its registered schema match.");
                }
                continue;
            }
            ManifestSection descriptor = ManifestSchemaEvidence.validatedSection(section);
            ArrayList<ValidatedManifestSection> matches =
                    mutable.computeIfAbsent(descriptor.path(), ignored -> new ArrayList<>());
            int placeholders = descriptor.path().placeholderNames().size();
            if (placeholders == 0 && !matches.isEmpty()) {
                throw duplicateSection(descriptor.path());
            }
            if (placeholders == 1) {
                String binding = descriptor.path().placeholderNames().getFirst();
                String key = section.schema().orElseThrow().bindings().get(binding);
                boolean duplicate = matches.stream().anyMatch(candidate -> Objects.equals(
                        candidate.schema().orElseThrow().bindings().get(binding), key));
                if (duplicate) {
                    throw new IllegalStateException(
                            "Duplicate validated manifest section `[" + section.path() + "]`.");
                }
            }
            matches.add(section);
        }
        IdentityHashMap<ManifestPath, List<ValidatedManifestSection>> immutable =
                new IdentityHashMap<>();
        mutable.forEach((handle, values) -> immutable.put(handle, List.copyOf(values)));
        return Collections.unmodifiableMap(immutable);
    }

    private ManifestField requireFieldHandle(ManifestField handle) {
        return ManifestSchemaEvidence.fieldHandle(handle);
    }

    private ManifestSection requireOwnedSectionEntry(SectionEntry entry) {
        Objects.requireNonNull(entry, "Manifest section entry is required.");
        ManifestSection descriptor = ManifestSchemaEvidence.validatedSection(entry.section());
        ManifestSection registered = ManifestSchemaEvidence.sectionHandle(descriptor.path());
        requirePlaceholderCount(registered.path(), 1, "section entry");
        String bindingName = registered.path().placeholderNames().getFirst();
        String boundKey = entry.section().schema().orElseThrow().bindings().get(bindingName);
        boolean retained = sections.getOrDefault(registered.path(), List.of()).stream()
                .anyMatch(candidate -> candidate == entry.section());
        if (!retained || !Objects.equals(boundKey, entry.key())) {
            throw new IllegalArgumentException(
                    "Manifest child-field access requires an exact retained section entry.");
        }
        return registered;
    }

    private static void requirePlaceholderCount(
            ManifestField handle,
            int expected,
            String accessKind) {
        int actual = handle.path().placeholderNames().size();
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "Manifest " + accessKind + " access requires " + expected
                            + " schema placeholder" + (expected == 1 ? "" : "s")
                            + " but `" + handle.path() + "` declares " + actual + ".");
        }
    }

    private static void requirePlaceholderCount(
            ManifestPath handle,
            int expected,
            String accessKind) {
        int actual = handle.placeholderNames().size();
        if (actual != expected) {
            throw new IllegalArgumentException(
                    "Manifest " + accessKind + " access requires " + expected
                            + " schema placeholder" + (expected == 1 ? "" : "s")
                            + " but `" + handle + "` declares " + actual + ".");
        }
    }

    private static void requireDirectChild(
            ManifestSection section,
            ManifestField field) {
        List<String> sectionPath = section.path().segments();
        List<String> fieldPath = field.path().segments();
        boolean directChild = fieldPath.size() == sectionPath.size() + 1
                && fieldPath.subList(0, sectionPath.size()).equals(sectionPath);
        if (!directChild
                || !field.path().placeholderNames().equals(section.path().placeholderNames())) {
            throw new IllegalArgumentException(
                    "Manifest field `" + field.path() + "` is not a direct child of named section `"
                            + section.path() + "`.");
        }
    }

    private static IllegalStateException duplicateField(ManifestPath path) {
        return new IllegalStateException(
                "Duplicate validated manifest field `" + path + "`.");
    }

    private static IllegalStateException duplicateSection(ManifestPath path) {
        return new IllegalStateException(
                "Duplicate validated manifest section `[" + path + "]`.");
    }

    record Entry(String key, ValidatedManifestField field) {
        Entry {
            Objects.requireNonNull(key, "Manifest entry key is required.");
            Objects.requireNonNull(field, "Validated manifest entry field is required.");
        }
    }

    record SectionEntry(String key, ValidatedManifestSection section) {
        SectionEntry {
            Objects.requireNonNull(key, "Manifest section entry key is required.");
            Objects.requireNonNull(section, "Validated manifest section entry is required.");
        }
    }
}
