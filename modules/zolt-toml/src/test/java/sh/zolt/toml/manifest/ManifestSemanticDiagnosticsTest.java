package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestCompilerFields;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestSchemaMatch;

final class ManifestSemanticDiagnosticsTest {
    @Test
    void mapsAuthoredSymbolsAndFailsClosedOnForgedValidatedEvidence() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [compiler]
                jdkApi = "release"
                """);
        ValidatedManifestField field = index
                .field(FinalManifestCompilerFields.COMPILER_JDK_API)
                .orElseThrow();

        assertEquals(
                "release",
                ManifestAuthoredSymbols.authored(
                        field, ManifestTomlValues.string(field),
                        new String[] {"release", "host"}, value -> value));
        assertEquals(
                "release",
                ManifestAuthoredSymbols.authored(
                        field,
                        ManifestTomlValues.string(field),
                        value -> value.equals("release")
                                ? Optional.of(value)
                                : Optional.empty()));

        ValidatedManifestField forged = new ValidatedManifestField(
                field.path(), field.schema(), "future", field.source());
        String message = "Final manifest schema accepted symbol `future` for "
                + "`compiler.jdkApi` but the authored model does not recognize it.";
        IllegalStateException candidatesFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestAuthoredSymbols.authored(
                        forged,
                        ManifestTomlValues.string(forged),
                        new String[] {"release", "host"},
                        value -> value));
        IllegalStateException lookupFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestAuthoredSymbols.authored(
                        forged,
                        ManifestTomlValues.string(forged),
                        value -> Optional.empty()));
        IllegalStateException schemaFailure = assertThrows(
                IllegalStateException.class,
                () -> ManifestAuthoredSymbols.model(
                        forged,
                        ManifestTomlValues.string(forged),
                        new String[] {"release", "host"},
                        value -> value,
                        "compiler JDK API mode"));

        assertEquals(message, candidatesFailure.getMessage());
        assertEquals(message, lookupFailure.getMessage());
        assertEquals(
                "Final manifest schema accepted compiler JDK API mode `future` at "
                        + "`compiler.jdkApi` but the model does not recognize it.",
                schemaFailure.getMessage());
    }

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
    void wrapsArrayItemsAndNestedArrayMembersAtExactIndexedPaths() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies]
                "org.example:demo" = { version = "1", exclude = ["org.bad:one", "org.bad:two"] }

                [dependencies.policy]
                deny = [
                    { coordinate = "org.bad:one" },
                    { coordinate = "org.bad:two" },
                ]
                """);
        ValidatedManifestField deny = index
                .field(FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY)
                .orElseThrow();
        ManifestInlineTable dependency = ManifestTomlValues.inlineObject(
                index.entries(FinalManifestDependencyFields.DEPENDENCIES_ENTRY)
                        .getFirst()
                        .field());
        IllegalArgumentException denyCause = new IllegalArgumentException("duplicate coordinate");
        IllegalArgumentException exclusionCause = new IllegalArgumentException("duplicate exclusion");

        ZoltConfigException denyFailure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.construct(deny, 1, () -> {
                    throw denyCause;
                }));
        ZoltConfigException exclusionFailure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestSemanticDiagnostics.construct(
                        dependency,
                        FinalManifestObjectShapes.DEPENDENCY_EXCLUDE,
                        1,
                        () -> {
                            throw exclusionCause;
                        }));

        assertEquals(
                "Invalid value for `dependencies.policy.deny[1]`: duplicate coordinate",
                denyFailure.getMessage());
        assertSame(denyCause, denyFailure.getCause());
        assertEquals(
                "Invalid value for `dependencies.org.example:demo.exclude[1]`: duplicate exclusion",
                exclusionFailure.getMessage());
        assertSame(exclusionCause, exclusionFailure.getCause());
    }

    @Test
    void indexedDiagnosticsRejectNegativeIndexesBeforeInvokingFactories() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies.policy]
                deny = [{ coordinate = "org.bad:one" }]
                """);
        ValidatedManifestField deny = index
                .field(FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY)
                .orElseThrow();
        int[] calls = {0};

        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestSemanticDiagnostics.construct(deny, -1, () -> {
                    calls[0]++;
                    return "unreachable";
                }));
        assertEquals(0, calls[0]);
    }

    @Test
    void indexedDiagnosticsRejectForgedScalarAndOutOfRangeContexts() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies]
                "org.example:demo" = { version = "1", exclude = ["org.bad:one"] }

                [dependencies.policy]
                deny = [{ coordinate = "org.bad:one" }]
                """);
        ValidatedManifestField deny = index
                .field(FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY)
                .orElseThrow();
        ValidatedManifestField forged = new ValidatedManifestField(
                deny.path(),
                new ManifestSchemaMatch<>(deny.schema().descriptor(), Map.of("id", "forged")),
                deny.rawValue(),
                deny.source());
        ManifestInlineTable dependency = ManifestTomlValues.inlineObject(
                index.entries(FinalManifestDependencyFields.DEPENDENCIES_ENTRY)
                        .getFirst()
                        .field());

        assertThrows(
                IllegalStateException.class,
                () -> ManifestSemanticDiagnostics.construct(forged, 0, () -> "forbidden"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestSemanticDiagnostics.construct(deny, 1, () -> "forbidden"));
        assertThrows(
                IllegalStateException.class,
                () -> ManifestSemanticDiagnostics.construct(
                        dependency,
                        FinalManifestObjectShapes.DEPENDENCY_VERSION,
                        0,
                        () -> "forbidden"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManifestSemanticDiagnostics.construct(
                        dependency,
                        FinalManifestObjectShapes.DEPENDENCY_EXCLUDE,
                        1,
                        () -> "forbidden"));
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
