package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSharedFields;

final class ManifestSemanticDiagnosticsTest {
    @Test
    void reportsRequiredFieldAndSectionHandlesWithFrozenText() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("");

        ZoltConfigException fieldFailure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.requiredField(
                        index, FinalManifestIdentityFields.PROJECT_NAME));
        ZoltConfigException sectionFailure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.requiredSection(
                        index, FinalManifestPaths.WORKSPACE_MEMBERS));

        assertEquals(
                "Missing required manifest field `project.name`.",
                fieldFailure.getMessage());
        assertEquals(
                "Missing required manifest section `[workspace.members]`.",
                sectionFailure.getMessage());
    }

    @Test
    void wrapsOnlyIllegalArgumentFailuresAtTheConcreteScalarPath() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                """);
        ValidatedManifestField field =
                index.field(FinalManifestIdentityFields.PROJECT_NAME).orElseThrow();
        IllegalArgumentException cause = new IllegalArgumentException("names must be lowercase");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.construct(field, () -> {
                    throw cause;
                }));

        assertEquals(
                "Invalid value for `project.name`: names must be lowercase",
                failure.getMessage());
        assertSame(cause, failure.getCause());
        assertEquals(
                "demo",
                ManifestSemanticDiagnostics.construct(
                        field, () -> ManifestTomlValues.string(field)));
    }

    @Test
    void wrapsNestedConstructionAtTheConcreteMemberPath() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                license = { id = "MIT" }
                """);
        ManifestInlineTable table = ManifestTomlValues.inlineObject(
                index.field(FinalManifestIdentityFields.PROJECT_LICENSE).orElseThrow());
        IllegalArgumentException cause = new IllegalArgumentException("unknown SPDX identifier");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.construct(
                        table,
                        FinalManifestObjectShapes.LICENSE_ID,
                        () -> {
                            throw cause;
                        }));

        assertEquals(
                "Invalid value for `project.license.id`: unknown SPDX identifier",
                failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    void derivesMissingNamedSectionChildrenFromExactDescriptorHandles() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [repositories.company]
                credentials = "release"
                """);
        ManifestDecodeIndex.SectionEntry repository =
                index.sectionEntries(FinalManifestPaths.REPOSITORY).getFirst();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.requiredField(
                        index,
                        repository,
                        FinalManifestSharedFields.REPOSITORY_URL));

        assertEquals(
                "Missing required manifest field `repositories.company.url`.",
                failure.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestSemanticDiagnostics.requiredField(
                        index,
                        repository,
                        FinalManifestSharedFields.CREDENTIAL_TOKEN_ENV));
    }

    @Test
    void wrapsSectionConstructionAtTheConcreteNamedPathAndRetainsCause() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [repositories.company]
                url = "https://repo.example"
                """);
        ValidatedManifestSection section = index
                .sectionEntries(FinalManifestPaths.REPOSITORY)
                .getFirst()
                .section();
        IllegalArgumentException cause = new IllegalArgumentException(
                "repository credentials are inconsistent");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.construct(section, () -> {
                    throw cause;
                }));

        assertEquals(
                "Invalid manifest section `[repositories.company]`: repository credentials are inconsistent",
                failure.getMessage());
        assertSame(cause, failure.getCause());

        IllegalStateException internal = new IllegalStateException("section invariant");
        assertSame(internal, assertThrows(
                IllegalStateException.class,
                () -> ManifestSemanticDiagnostics.construct(section, () -> {
                    throw internal;
                })));
    }

    @Test
    void internalAndAlreadyActionableFailuresEscapeUnchanged() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [project]
                name = "demo"
                """);
        ValidatedManifestField field =
                index.field(FinalManifestIdentityFields.PROJECT_NAME).orElseThrow();
        IllegalStateException internal = new IllegalStateException("decoder invariant");
        ZoltConfigException actionable = new ZoltConfigException("already contextualized");

        IllegalStateException observedInternal = assertThrows(
                IllegalStateException.class,
                () -> ManifestSemanticDiagnostics.construct(field, () -> {
                    throw internal;
                }));
        ZoltConfigException observedActionable = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.construct(field, () -> {
                    throw actionable;
                }));

        assertSame(internal, observedInternal);
        assertSame(actionable, observedActionable);
    }
}
