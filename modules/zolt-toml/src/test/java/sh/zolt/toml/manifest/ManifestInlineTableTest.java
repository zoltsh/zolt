package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;

final class ManifestInlineTableTest {
    @Test
    void readsLicenseAndCentralOnlyThroughTheirOwnedMemberHandles() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                license = { id = "MIT", url = "https://spdx.org/licenses/MIT.html" }

                [repositories]
                central = { url = "https://repo.example", credentials = "company" }
                """);
        ManifestInlineTable license = ManifestTomlValues.inlineObject(
                index.field(FinalManifestIdentityFields.PROJECT_LICENSE).orElseThrow());
        ManifestInlineTable central = ManifestTomlValues.inlineObject(
                index.field(FinalManifestSharedFields.REPOSITORIES_CENTRAL).orElseThrow());

        assertEquals(
                "MIT",
                license.optionalString(FinalManifestObjectShapes.LICENSE_ID).orElseThrow());
        assertTrue(license.optionalString(FinalManifestObjectShapes.LICENSE_NAME).isEmpty());
        assertEquals(
                "https://repo.example",
                central.requiredString(FinalManifestObjectShapes.CENTRAL_URL));
        assertEquals(
                "company",
                central.optionalString(FinalManifestObjectShapes.CENTRAL_CREDENTIALS)
                        .orElseThrow());
        assertSame(
                index.field(FinalManifestIdentityFields.PROJECT_LICENSE).orElseThrow().source(),
                license.source());
    }

    @Test
    void derivesNestedDynamicPathsFromTheConcreteValidatedParent() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [platforms]
                "org.example:demo" = { versionRef = "release" }
                """);
        ManifestDecodeIndex.Entry entry =
                index.entries(FinalManifestSharedFields.PLATFORMS_ENTRY).getFirst();
        ManifestInlineTable platform = ManifestTomlValues.inlineObject(entry.field());

        assertEquals("org.example:demo", entry.key());
        assertEquals(
                ManifestPath.of("platforms", "org.example:demo", "versionRef"),
                platform.path(FinalManifestObjectShapes.PLATFORM_VERSION_REF).structure());
        assertEquals(
                "release",
                platform.requiredString(FinalManifestObjectShapes.PLATFORM_VERSION_REF));
    }

    @Test
    void readsOptionalBooleansAndImmutableStringArraysWithoutCollapsingPresence() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies]
                "org.example:demo" = { version = "1.0", optional = false, exclude = [] }
                """);
        ManifestInlineTable dependency = ManifestTomlValues.inlineObject(
                index.entries(FinalManifestDependencyFields.DEPENDENCIES_ENTRY)
                        .getFirst()
                        .field());

        assertEquals(
                false,
                dependency.optionalBoolean(FinalManifestObjectShapes.DEPENDENCY_OPTIONAL)
                        .orElseThrow());
        assertTrue(dependency.optionalBoolean(
                FinalManifestObjectShapes.DEPENDENCY_PUBLISH_ONLY).isEmpty());
        List<String> exclusions = dependency
                .optionalStrings(FinalManifestObjectShapes.DEPENDENCY_EXCLUDE)
                .orElseThrow();
        assertTrue(exclusions.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> exclusions.add("org.bad:one"));
    }

    @Test
    void exposesExactIndexedMemberPathsForArrayItems() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies.policy]
                deny = [
                    { coordinate = "org.bad:one" },
                    { coordinate = "org.bad:two", reason = "blocked" },
                ]
                """);
        ValidatedManifestField field = index
                .field(FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY)
                .orElseThrow();
        ManifestInlineTable second = ManifestTomlValues.inlineObjectArray(field).get(1);

        assertEquals(
                "dependencies.policy.deny[1].coordinate",
                second.path(FinalManifestObjectShapes.DENY_ENTRY_COORDINATE).toString());
        assertEquals(
                "dependencies.policy.deny[1].reason",
                second.path(FinalManifestObjectShapes.DENY_ENTRY_REASON).toString());
        assertThrows(
                IllegalStateException.class,
                () -> second.indexedPath(FinalManifestObjectShapes.DENY_ENTRY_REASON, 0));
        assertSame(field.source(), second.source());
        assertFalse(second.path(FinalManifestObjectShapes.DENY_ENTRY_REASON)
                .toString().contains(".["));
        assertThrows(IllegalArgumentException.class, () -> ManifestInlineTable.indexed(field, -1));
        assertThrows(IllegalArgumentException.class, () -> ManifestInlineTable.indexed(field, 2));
    }

    @Test
    void rejectsEqualClonesAndHandlesOwnedByAnotherShape() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                license = { id = "MIT" }
                """);
        ManifestInlineTable license = ManifestTomlValues.inlineObject(
                index.field(FinalManifestIdentityFields.PROJECT_LICENSE).orElseThrow());
        ManifestObjectMember member = FinalManifestObjectShapes.LICENSE_ID;
        ManifestObjectMember clone = new ManifestObjectMember(
                member.name(), member.valueKind(), member.required(), member.canonicalOrder());

        assertThrows(IllegalArgumentException.class, () -> license.optionalString(clone));
        assertThrows(
                IllegalArgumentException.class,
                () -> license.optionalString(FinalManifestObjectShapes.CENTRAL_URL));
    }

    @Test
    void rejectsWrongMemberAccessorsEvenWhenTheOptionalMemberIsAbsent() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies]
                "org.example:demo" = { version = "1.0" }
                """);
        ManifestInlineTable dependency = ManifestTomlValues.inlineObject(
                index.entries(FinalManifestDependencyFields.DEPENDENCIES_ENTRY)
                        .getFirst()
                        .field());

        assertThrows(
                IllegalStateException.class,
                () -> dependency.optionalString(
                        FinalManifestObjectShapes.DEPENDENCY_OPTIONAL));
        assertThrows(
                IllegalStateException.class,
                () -> dependency.optionalBoolean(
                        FinalManifestObjectShapes.DEPENDENCY_CLASSIFIER));
        assertThrows(
                IllegalStateException.class,
                () -> dependency.optionalStrings(
                        FinalManifestObjectShapes.DEPENDENCY_TYPE));
    }

    @Test
    void rejectsScalarBranchesAndRegisteredTablesWithoutClosedShapes() {
        ManifestDecodeIndex scalarIndex = ManifestSemanticTestSupport.index("""
                [project]
                license = "MIT"
                """);
        ValidatedManifestField scalar =
                scalarIndex.field(FinalManifestIdentityFields.PROJECT_LICENSE).orElseThrow();
        IllegalStateException scalarFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestTomlValues.inlineObject(scalar));
        assertTrue(scalarFailure.getMessage().contains("project.license"));
        assertTrue(scalarFailure.getMessage().contains("string"));

        ManifestDecodeIndex openIndex = ManifestSemanticTestSupport.index("""
                [test.runtime]
                properties = { answer = "yes" }
                """);
        ManifestField openHandle = FinalManifestSchema.registry()
                .field(ManifestPath.of("test", "runtime", "properties"))
                .orElseThrow();
        ValidatedManifestField open = openIndex.field(openHandle).orElseThrow();
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestTomlValues.inlineObject(open));
    }

    @Test
    void missingRequiredAndWrongNestedRawKindsRemainInternalFailures() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [repositories]
                central = { url = "https://repo.example" }
                """);
        ValidatedManifestField valid =
                index.field(FinalManifestSharedFields.REPOSITORIES_CENTRAL).orElseThrow();
        ManifestInlineTable missing = corruptInlineTable(valid, "value = { credentials = \"company\" }");
        ManifestInlineTable wrong = corruptInlineTable(valid, "value = { url = 42 }");

        IllegalStateException missingFailure = assertThrows(
                IllegalStateException.class,
                () -> missing.requiredString(FinalManifestObjectShapes.CENTRAL_URL));
        assertTrue(missingFailure.getMessage().contains("repositories.central.url"));

        IllegalStateException wrongFailure = assertThrows(
                IllegalStateException.class,
                () -> wrong.requiredString(FinalManifestObjectShapes.CENTRAL_URL));
        assertTrue(wrongFailure.getMessage().contains("repositories.central.url"));
        assertTrue(wrongFailure.getMessage().contains("integer"));
    }

    private static ManifestInlineTable corruptInlineTable(
            ValidatedManifestField field,
            String source) {
        ValidatedManifestField corrupt = new ValidatedManifestField(
                field.path(),
                new ManifestSchemaMatch<>(field.schema().descriptor(), field.schema().bindings()),
                Toml.parse(source).getTable("value"),
                field.source());
        return new ManifestInlineTable(corrupt);
    }
}
