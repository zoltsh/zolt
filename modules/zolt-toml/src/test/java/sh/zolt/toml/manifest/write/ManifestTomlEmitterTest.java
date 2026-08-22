package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import sh.zolt.toml.schema.FinalManifestCommandFields;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestIdentityFields;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestSharedFields;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

final class ManifestTomlEmitterTest {
    @Test
    void emitsOnlyNonemptySectionsWithCanonicalSpacingAndOneTerminalLf() {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        emitter.section(section(FinalManifestPaths.WORKSPACE));
        emitter.field(FinalManifestIdentityFields.WORKSPACE_NAME, "\"demo\"");
        emitter.section(section(FinalManifestPaths.WORKSPACE_MEMBERS));
        emitter.section(section(FinalManifestPaths.PROJECT));
        emitter.field(FinalManifestIdentityFields.PROJECT_NAME, "\"app\"");
        emitter.field(FinalManifestIdentityFields.PROJECT_VERSION, "\"1.0.0\"");

        String result = emitter.finish();

        assertEquals("""
                [workspace]
                name = "demo"

                [project]
                name = "app"
                version = "1.0.0"
                """, result);
        assertTrue(result.endsWith("\n"));
        assertFalse(result.endsWith("\n\n"));
    }

    @Test
    void emitsOrderedDynamicFieldsAndNamedSectionsUsingCanonicalTomlKeys() {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        emitter.section(section(FinalManifestPaths.VERSIONS));
        emitter.dynamicField(FinalManifestSharedFields.VERSIONS_ENTRY, "alpha", "\"1.0\"");
        emitter.dynamicField(FinalManifestSharedFields.VERSIONS_ENTRY, "spring-boot", "\"4.0.6\"");
        emitter.namedSection(section(FinalManifestPaths.REPOSITORY), "internal");
        emitter.field(FinalManifestSharedFields.REPOSITORY_URL, "\"https://repo.example.test\"");
        emitter.section(section(FinalManifestPaths.DEPENDENCIES));
        emitter.dynamicField(
                FinalManifestDependencyFields.DEPENDENCIES_ENTRY,
                "com.example:alpha",
                "{ managed = true }");
        emitter.dynamicField(
                FinalManifestDependencyFields.DEPENDENCIES_ENTRY,
                "org.example:zeta",
                "\"1.0\"");
        emitter.namedSection(
                section(FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION),
                "org.example:blocked");
        emitter.field(
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_ALLOW,
                "[\"Apache-2.0\"]");

        assertEquals("""
                [versions]
                alpha = "1.0"
                spring-boot = "4.0.6"

                [repositories.internal]
                url = "https://repo.example.test"

                [dependencies]
                "com.example:alpha" = { managed = true }
                "org.example:zeta" = "1.0"

                [dependencies.license-exceptions."org.example:blocked"]
                allow = ["Apache-2.0"]
                """, emitter.finish());
    }

    @Test
    void acceptsMultilineValuesExceptForSchemaMarkedOneLineFields() {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        emitter.section(section(FinalManifestPaths.WORKSPACE_MEMBERS));
        emitter.field(
                FinalManifestIdentityFields.WORKSPACE_MEMBERS_INCLUDE,
                "[\n    \"apps/*\",\n    \"modules/*\",\n]");

        assertEquals("""
                [workspace.members]
                include = [
                    "apps/*",
                    "modules/*",
                ]
                """, emitter.finish());

        ManifestTomlEmitter oneLine = new ManifestTomlEmitter();
        oneLine.section(section(FinalManifestPaths.DEPENDENCIES));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> oneLine.dynamicField(
                        FinalManifestDependencyFields.DEPENDENCIES_ENTRY,
                        "org.example:demo",
                        "{ version =\n    \"1.0\" }"));
        assertTrue(failure.getMessage().contains("one physical line"));
    }

    @Test
    void ordersDynamicKeysByUnicodeCodePointRatherThanUtf16CodeUnit() {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        emitter.section(section(FinalManifestPaths.PACKAGE_MANIFEST));
        emitter.dynamicField(FinalManifestPackagingFields.PACKAGE_MANIFEST_ENTRY, "\uE000", "\"bmp\"");
        emitter.dynamicField(
                FinalManifestPackagingFields.PACKAGE_MANIFEST_ENTRY,
                "\uD800\uDC00",
                "\"supplementary\"");

        assertEquals(
                "[package.manifest]\n\"\uE000\" = \"bmp\"\n\"\uD800\uDC00\" = \"supplementary\"\n",
                emitter.finish());
    }

    @Test
    void rejectsDynamicKeysThatCannotBeEncodedAsToml() {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        emitter.section(section(FinalManifestPaths.PACKAGE_MANIFEST));

        assertThrows(
                IllegalArgumentException.class,
                () -> emitter.dynamicField(
                        FinalManifestPackagingFields.PACKAGE_MANIFEST_ENTRY,
                        "broken\uD800",
                        "\"value\""));
    }

    @Test
    void rejectsEmptyInlineTablesButNotBracesInsideAString() {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        emitter.section(section(FinalManifestPaths.DEPENDENCIES));

        IllegalArgumentException direct = assertThrows(
                IllegalArgumentException.class,
                () -> emitter.dynamicField(
                        FinalManifestDependencyFields.DEPENDENCIES_ENTRY,
                        "org.example:empty",
                        "{ }"));
        assertTrue(direct.getMessage().contains("empty inline table"));

        emitter.section(section(FinalManifestPaths.DEPENDENCY_POLICY));
        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> emitter.field(
                        FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY,
                        "[{ }, { coordinate = \"org.example:bad\" }]"));
        assertTrue(nested.getMessage().contains("empty inline table"));

        ManifestTomlEmitter string = new ManifestTomlEmitter();
        string.section(section(FinalManifestPaths.PROJECT));
        string.field(FinalManifestIdentityFields.PROJECT_DESCRIPTION, "\"{}\"");
        assertEquals("[project]\ndescription = \"{}\"\n", string.finish());
    }

    @Test
    void acceptsOnlyExactRegisteredSectionAndFieldHandles() {
        ManifestSection project = section(FinalManifestPaths.PROJECT);
        ManifestSection forgedSection = new ManifestSection(
                new ManifestPath(project.path().segments()),
                project.kind(),
                project.canonicalOrder(),
                project.reservedChildren(),
                project.dynamicKeyGrammars());
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();

        IllegalArgumentException sectionFailure = assertThrows(
                IllegalArgumentException.class,
                () -> emitter.section(forgedSection));
        assertTrue(sectionFailure.getMessage().contains("exact registered schema handle"));

        emitter.section(project);
        ManifestField name = FinalManifestIdentityFields.PROJECT_NAME;
        ManifestField forgedField = new ManifestField(
                new ManifestPath(name.path().segments()),
                name.valueKind(),
                name.formatting(),
                name.mutation(),
                name.canonicalOrder(),
                name.symbolFamily(),
                name.validation(),
                name.dynamicKeyGrammars(),
                name.objectShape());
        IllegalArgumentException fieldFailure = assertThrows(
                IllegalArgumentException.class,
                () -> emitter.field(forgedField, "\"demo\""));
        assertTrue(fieldFailure.getMessage().contains("exact registered schema handle"));
    }

    @Test
    void rejectsFieldsSectionsAndDynamicKeysThatMoveBackward() {
        ManifestTomlEmitter fields = new ManifestTomlEmitter();
        fields.section(section(FinalManifestPaths.PROJECT));
        fields.field(FinalManifestIdentityFields.PROJECT_VERSION, "\"1.0\"");
        IllegalStateException fieldFailure = assertThrows(
                IllegalStateException.class,
                () -> fields.field(FinalManifestIdentityFields.PROJECT_NAME, "\"demo\""));
        assertTrue(fieldFailure.getMessage().contains("out of canonical order"));

        ManifestTomlEmitter keys = new ManifestTomlEmitter();
        keys.section(section(FinalManifestPaths.VERSIONS));
        keys.dynamicField(FinalManifestSharedFields.VERSIONS_ENTRY, "zeta", "\"1.0\"");
        IllegalStateException keyFailure = assertThrows(
                IllegalStateException.class,
                () -> keys.dynamicField(
                        FinalManifestSharedFields.VERSIONS_ENTRY,
                        "alpha",
                        "\"1.0\""));
        assertTrue(keyFailure.getMessage().contains("out of canonical order"));

        ManifestTomlEmitter sections = new ManifestTomlEmitter();
        sections.section(section(FinalManifestPaths.PROJECT));
        sections.field(FinalManifestIdentityFields.PROJECT_NAME, "\"demo\"");
        sections.section(section(FinalManifestPaths.WORKSPACE));
        sections.field(FinalManifestIdentityFields.WORKSPACE_NAME, "\"workspace\"");
        IllegalStateException sectionFailure = assertThrows(
                IllegalStateException.class,
                sections::finish);
        assertTrue(sectionFailure.getMessage().contains("out of canonical order"));
    }

    @Test
    void rejectsWrongSectionInvalidDynamicKeysAndReservedNames() {
        ManifestTomlEmitter wrongSection = new ManifestTomlEmitter();
        wrongSection.section(section(FinalManifestPaths.PROJECT));
        IllegalArgumentException misplaced = assertThrows(
                IllegalArgumentException.class,
                () -> wrongSection.field(FinalManifestSharedFields.REPOSITORIES_CENTRAL, "true"));
        assertTrue(misplaced.getMessage().contains("does not belong"));
        assertThrows(
                IllegalArgumentException.class,
                () -> wrongSection.dynamicField(
                        FinalManifestIdentityFields.PROJECT_NAME, null, "\"demo\""));

        ManifestTomlEmitter invalidName = new ManifestTomlEmitter();
        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> invalidName.namedSection(section(FinalManifestPaths.REPOSITORY), "Not Valid"));
        assertTrue(invalid.getMessage().contains("lowercase kebab-case"));

        ManifestTomlEmitter reserved = new ManifestTomlEmitter();
        reserved.section(section(FinalManifestPaths.ALIASES));
        IllegalArgumentException reservedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> reserved.dynamicField(FinalManifestCommandFields.ALIASES_ENTRY, "build", "\"test\""));
        assertTrue(reservedFailure.getMessage().contains("reserved"));
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}
