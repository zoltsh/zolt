package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestIdentityFields;

final class ManifestLicenseDecoderTest {
    private final ManifestLicenseDecoder decoder = new ManifestLicenseDecoder();

    @Test
    void canonicalizesTheSingleSpdxIdentifierShorthand() {
        ProjectLicense.Identifier license = assertInstanceOf(
                ProjectLicense.Identifier.class,
                decodeProjectLicense("\"apache-2.0\""));

        assertEquals("Apache-2.0", license.id());
    }

    @Test
    void retainsInlineCustomAndCompoundMetadataWithoutSpdxShorthandRules() {
        ProjectLicense.Metadata license = assertInstanceOf(
                ProjectLicense.Metadata.class,
                decodeProjectLicense("""
                        { id = "Apache-2.0 OR LicenseRef-Custom", name = "  House terms  ", url = "https://example.com/license" }
                        """));

        assertEquals("Apache-2.0 OR LicenseRef-Custom", license.id().orElseThrow());
        assertEquals("  House terms  ", license.name().orElseThrow());
        assertEquals("https://example.com/license", license.url().orElseThrow());

        ProjectLicense.Metadata minimal = assertInstanceOf(
                ProjectLicense.Metadata.class,
                decodeProjectLicense("{ name = \"Private license\" }"));
        assertEquals(Optional.empty(), minimal.id());
        assertEquals(Optional.empty(), minimal.url());
    }

    @Test
    void rejectsExpressionsAndUnknownIdsOnlyInTheStringShorthand() {
        assertFailure(
                "\"Apache-2.0 OR MIT\"",
                "Invalid value for `project.license`: Project license shorthand requires one current SPDX");
        assertFailure(
                "\"LicenseRef-Custom\"",
                "Invalid value for `project.license`: Project license shorthand requires one current SPDX");
    }

    @Test
    void anchorsBlankInlineValuesToTheirNestedMemberPaths() {
        assertFailure(
                "{ id = \"\" }",
                "Invalid value for `project.license.id`: Project license metadata id must not be blank");
        assertFailure(
                "{ name = \"  \" }",
                "Invalid value for `project.license.name`: Project license metadata name must not be blank");
        assertFailure(
                "{ id = \"MIT\", url = \"\\t\" }",
                "Invalid value for `project.license.url`: Project license metadata URL must not be blank");
    }

    @Test
    void leavesClosedObjectPresenceToTheSchemaValidator() {
        assertFailure(
                "{ url = \"https://example.com/license\" }",
                "Inline object `project.license` must declare at least one of `id` or `name`");
    }

    @Test
    void usesTheSameLicenseDecoderForWorkspaceProjectDefaults() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [workspace.project]
                license = "mit"
                """);
        ValidatedManifestField field = index
                .field(FinalManifestIdentityFields.WORKSPACE_PROJECT_LICENSE)
                .orElseThrow();
        ProjectLicense.Identifier license = assertInstanceOf(
                ProjectLicense.Identifier.class, decoder.decode(field));

        assertEquals("MIT", license.id());
    }

    private ProjectLicense decodeProjectLicense(String value) {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index(
                "[project]\nlicense = " + value + "\n");
        return decoder.decode(index.field(FinalManifestIdentityFields.PROJECT_LICENSE).orElseThrow());
    }

    private void assertFailure(String value, String expected) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decodeProjectLicense(value));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
