package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
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
    }

    private static ValidatedManifestField required(
            ManifestDecodeIndex index,
            sh.zolt.toml.schema.ManifestField handle) {
        return index.field(handle).orElseThrow();
    }
}
