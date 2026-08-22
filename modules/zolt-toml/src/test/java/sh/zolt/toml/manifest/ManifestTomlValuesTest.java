package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.toml.schema.FinalManifestCoverageFields;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestGeneratedPresetFields;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestValueKind;

final class ManifestTomlValuesTest {
    @Test
    void readsInitialScalarAndArrayKindsWithoutNarrowingOrMutableResults() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                java = 9223372036854775807

                [workspace.members]
                include = ["modules/*", "apps/*"]

                [repositories]
                central = true
                """);
        ValidatedManifestField name = required(index, FinalManifestIdentityFields.PROJECT_NAME);
        ValidatedManifestField java = required(index, FinalManifestIdentityFields.PROJECT_JAVA);
        ValidatedManifestField members =
                required(index, FinalManifestIdentityFields.WORKSPACE_MEMBERS_INCLUDE);
        ValidatedManifestField central =
                required(index, FinalManifestSharedFields.REPOSITORIES_CENTRAL);

        assertEquals("demo", ManifestTomlValues.string(name));
        assertEquals(Long.MAX_VALUE, ManifestTomlValues.integer(java));
        assertEquals(List.of("modules/*", "apps/*"), ManifestTomlValues.strings(members));
        assertTrue(ManifestTomlValues.booleanValue(central));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ManifestTomlValues.strings(members).add("other"));
    }

    @Test
    void readsIntegerAndFractionalNumbersOnlyThroughRegisteredNumberFields() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                java = 21

                [coverage]
                line = 88
                branch = 74.5
                """);
        ValidatedManifestField java = required(index, FinalManifestIdentityFields.PROJECT_JAVA);
        ValidatedManifestField line = required(index, FinalManifestCoverageFields.COVERAGE_LINE);
        ValidatedManifestField branch = required(index, FinalManifestCoverageFields.COVERAGE_BRANCH);

        assertEquals(88.0, ManifestTomlValues.number(line));
        assertEquals(74.5, ManifestTomlValues.number(branch));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.number(java));
        assertTrue(failure.getMessage().contains("project.java"));
        assertTrue(failure.getMessage().contains("cannot be read as number"));
    }

    @Test
    void classifiesEveryInitialUnionBranchAfterCheckingItsRegisteredKind() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                license = "MIT"

                [repositories]
                central = false

                [platforms]
                "org.example:string" = "1.0"
                "org.example:object" = { versionRef = "release" }
                """);
        ValidatedManifestField license =
                required(index, FinalManifestIdentityFields.PROJECT_LICENSE);
        ValidatedManifestField central =
                required(index, FinalManifestSharedFields.REPOSITORIES_CENTRAL);
        List<ManifestDecodeIndex.Entry> platforms =
                index.entries(FinalManifestSharedFields.PLATFORMS_ENTRY);

        assertTrue(ManifestTomlValues.isString(license));
        assertFalse(ManifestTomlValues.isBoolean(license));
        assertTrue(ManifestTomlValues.isBoolean(central));
        assertTrue(ManifestTomlValues.isString(platforms.get(0).field()));
        assertTrue(ManifestTomlValues.isInlineObject(platforms.get(1).field()));
        assertEquals(
                "release",
                ManifestTomlValues.inlineObject(platforms.get(1).field())
                        .requiredString(sh.zolt.toml.schema.FinalManifestObjectShapes.PLATFORM_VERSION_REF));
    }

    @Test
    void wrongAccessorsRemainInternalFailuresWithConcretePathAndActualKind() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                java = 21
                """);
        ValidatedManifestField name = required(index, FinalManifestIdentityFields.PROJECT_NAME);
        ValidatedManifestField java = required(index, FinalManifestIdentityFields.PROJECT_JAVA);

        IllegalStateException stringFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.string(java));
        assertTrue(stringFailure.getMessage().contains("project.java"));
        assertTrue(stringFailure.getMessage().contains("integer"));

        IllegalStateException arrayFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.strings(name));
        assertTrue(arrayFailure.getMessage().contains("project.name"));
        assertTrue(arrayFailure.getMessage().contains("string"));
    }

    @Test
    void impossibleRawKindsFailBeforeBranchClassification() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                """);
        ValidatedManifestField field = required(index, FinalManifestIdentityFields.PROJECT_NAME);
        ValidatedManifestField corrupt = new ValidatedManifestField(
                field.path(),
                new ManifestSchemaMatch<>(field.schema().descriptor(), field.schema().bindings()),
                42L,
                field.source());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.isString(corrupt));

        assertTrue(failure.getMessage().contains("project.name"));
        assertTrue(failure.getMessage().contains("integer"));
    }

    @Test
    void readsClosedInlineObjectArraysInSourceOrderWithImmutableResults() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies.policy]
                deny = [
                    { coordinate = "org.bad:one" },
                    { coordinate = "org.bad:two", reason = "blocked" },
                ]
                """);
        ValidatedManifestField field = required(
                index, FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY);

        List<ManifestInlineTable> entries = ManifestTomlValues.inlineObjectArray(field);

        assertEquals(2, entries.size());
        assertEquals(
                "org.bad:one",
                entries.get(0).requiredString(
                        FinalManifestObjectShapes.DENY_ENTRY_COORDINATE));
        assertEquals(
                "org.bad:two",
                entries.get(1).requiredString(
                        FinalManifestObjectShapes.DENY_ENTRY_COORDINATE));
        assertThrows(UnsupportedOperationException.class, () -> entries.add(entries.getFirst()));
    }

    @Test
    void readsOpenStringMapsInSourceOrderWithoutNormalizingKeysOrValues() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [generated.presets.client]
                kind = "openapi"
                options = { zKey = "", "Case.Key" = "line one\\nline two", alpha = "value" }
                additionalProperties = {}
                """);
        ValidatedManifestField options = entry(
                index, FinalManifestGeneratedPresetFields.GENERATED_PRESET_OPTIONS);
        ValidatedManifestField additional = entry(
                index,
                FinalManifestGeneratedPresetFields.GENERATED_PRESET_ADDITIONAL_PROPERTIES);

        Map<String, String> values = ManifestTomlValues.stringMap(options);
        Map<String, String> empty = ManifestTomlValues.stringMap(additional);

        assertEquals(List.of("zKey", "Case.Key", "alpha"), List.copyOf(values.keySet()));
        assertEquals("", values.get("zKey"));
        assertEquals("line one\nline two", values.get("Case.Key"));
        assertTrue(empty.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> values.put("other", "value"));
        assertThrows(UnsupportedOperationException.class, () -> empty.put("other", "value"));
    }

    @Test
    void reportsNonStringMapMembersAsAuthoredSemanticFailures() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [generated.presets.client]
                kind = "openapi"
                options = { valid = "value", count = 2 }
                """);
        ValidatedManifestField field = entry(
                index, FinalManifestGeneratedPresetFields.GENERATED_PRESET_OPTIONS);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValues.stringMap(field));

        assertTrue(failure.getMessage().contains("generated.presets.client.options"));
        assertTrue(failure.getMessage().contains("key `count`"));
        assertTrue(failure.getMessage().contains("integer"));
    }

    @Test
    void rejectsWrongStringMapAccessorsClosedShapesAndCorruptRawValues() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [generated.presets.client]
                kind = "openapi"
                options = { value = "ok" }

                [resources.tokens]
                release = { value = "1.0.0" }
                """);
        ValidatedManifestField scalar = entry(
                index, FinalManifestGeneratedPresetFields.GENERATED_PRESET_KIND);
        ValidatedManifestField map = entry(
                index, FinalManifestGeneratedPresetFields.GENERATED_PRESET_OPTIONS);
        ManifestField tokenHandle = FinalManifestSchema.registry()
                .field(ManifestPath.of("resources", "tokens", "<id>"))
                .orElseThrow();
        ValidatedManifestField closed = index.entries(tokenHandle).getFirst().field();

        assertThrows(IllegalStateException.class, () -> ManifestTomlValues.stringMap(scalar));
        assertThrows(IllegalStateException.class, () -> ManifestTomlValues.stringMap(closed));

        ValidatedManifestField corrupt = new ValidatedManifestField(
                map.path(), map.schema(), "not a table", map.source());
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.stringMap(corrupt));
        assertTrue(failure.getMessage().contains("generated.presets.client.options"));
        assertTrue(failure.getMessage().contains("string"));
    }

    @Test
    void rejectsForgedStringMapDescriptorsAndBindingsBeforeRawAccess() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [generated.presets.client]
                kind = "openapi"
                options = { value = "ok" }
                """);
        ValidatedManifestField field = entry(
                index, FinalManifestGeneratedPresetFields.GENERATED_PRESET_OPTIONS);
        ManifestField original = field.schema().descriptor();
        ManifestField forgedHandle = new ManifestField(
                new ManifestPath(original.path().segments()),
                original.valueKind(),
                original.formatting(),
                original.mutation(),
                original.canonicalOrder(),
                original.symbolFamily(),
                original.validation(),
                original.dynamicKeyGrammars(),
                original.objectShape());
        ValidatedManifestField forgedDescriptor = new ValidatedManifestField(
                field.path(),
                new ManifestSchemaMatch<>(forgedHandle, field.schema().bindings()),
                field.rawValue(),
                field.source());
        ValidatedManifestField forgedBinding = new ValidatedManifestField(
                field.path(),
                new ManifestSchemaMatch<>(original, Map.of("id", "forged")),
                field.rawValue(),
                field.source());

        for (ValidatedManifestField forged : List.of(forgedDescriptor, forgedBinding)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> ManifestTomlValues.stringMap(forged));
            assertTrue(failure.getMessage().contains("exact registered schema match"));
        }
    }

    @Test
    void rejectsNonArrayAndCorruptArrayEvidence() {
        ManifestDecodeIndex scalarIndex = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                """);
        assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.inlineObjectArray(
                        required(scalarIndex, FinalManifestIdentityFields.PROJECT_NAME)));

        ManifestDecodeIndex denyIndex = ManifestSemanticTestSupport.index("""
                [dependencies.policy]
                deny = [{ coordinate = "org.bad:one" }]
                """);
        ValidatedManifestField deny = required(
                denyIndex, FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY);
        ValidatedManifestField corrupt = new ValidatedManifestField(
                deny.path(),
                deny.schema(),
                Toml.parse("value = [42]").getArray("value"),
                deny.source());
        assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.inlineObjectArray(corrupt));
    }

    @Test
    void rejectsForgedDescriptorTypesAndDynamicBindingsBeforeRawAccess() {
        ManifestDecodeIndex staticIndex = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                """);
        ValidatedManifestField name =
                required(staticIndex, FinalManifestIdentityFields.PROJECT_NAME);
        ManifestField original = name.schema().descriptor();
        ManifestField forgedType = new ManifestField(
                new ManifestPath(original.path().segments()),
                ManifestValueKind.INTEGER,
                original.formatting(),
                original.mutation(),
                original.canonicalOrder(),
                original.symbolFamily(),
                original.validation(),
                original.dynamicKeyGrammars(),
                original.objectShape());
        ValidatedManifestField forgedDescriptor = new ValidatedManifestField(
                name.path(),
                new ManifestSchemaMatch<>(forgedType, name.schema().bindings()),
                42L,
                name.source());

        IllegalStateException descriptorFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.integer(forgedDescriptor));
        assertTrue(descriptorFailure.getMessage().contains("exact registered schema match"));

        ManifestDecodeIndex dynamicIndex = ManifestSemanticTestSupport.index("""
                [versions]
                release = "1.0"
                """);
        ValidatedManifestField release = dynamicIndex
                .entries(FinalManifestSharedFields.VERSIONS_ENTRY)
                .getFirst()
                .field();
        ValidatedManifestField forgedBinding = new ValidatedManifestField(
                release.path(),
                new ManifestSchemaMatch<>(release.schema().descriptor(), Map.of("id", "forged")),
                release.rawValue(),
                release.source());

        IllegalStateException bindingFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.string(forgedBinding));
        assertTrue(bindingFailure.getMessage().contains("exact registered schema match"));

        ManifestDecodeIndex denyIndex = ManifestSemanticTestSupport.index("""
                [dependencies.policy]
                deny = [{ coordinate = "org.bad:one" }]
                """);
        ValidatedManifestField deny = required(
                denyIndex, FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY);
        ValidatedManifestField forgedArrayBinding = new ValidatedManifestField(
                deny.path(),
                new ManifestSchemaMatch<>(deny.schema().descriptor(), Map.of("id", "forged")),
                deny.rawValue(),
                deny.source());

        assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.inlineObjectArray(forgedArrayBinding));
    }

    private static ValidatedManifestField required(
            ManifestDecodeIndex index,
            sh.zolt.toml.schema.ManifestField handle) {
        return index.field(handle).orElseThrow();
    }

    private static ValidatedManifestField entry(
            ManifestDecodeIndex index,
            ManifestField handle) {
        return index.entries(handle).getFirst().field();
    }
}
