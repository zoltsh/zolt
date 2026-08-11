package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.license.SpdxCatalog;

final class CycloneDxSpdxCatalogTest {
    private static final String TOOL_VERSION = "0.1.0-TEST";
    private final CycloneDxSbomWriter writer = new CycloneDxSbomWriter();

    @Test
    void emitsNewerSpdxIdentifierAsNamedLicenseForCycloneDx15() {
        String json = writer.write(model(List.of(catalogComponent("3D-Slicer-1.0"))));

        assertTrue(json.contains("\"name\": \"3D-Slicer-1.0\""), json);
        assertTrue(!json.contains("\"id\": \"3D-Slicer-1.0\""), json);
        CycloneDxSchemaValidator.assertValid(json);
    }

    @Test
    void everyActiveParserLicenseProducesCycloneDx15SchemaValidOutput() {
        List<SbomComponent> components = SpdxCatalog.defaultCatalog().licenseIds().stream()
                .map(CycloneDxSpdxCatalogTest::catalogComponent)
                .toList();

        String json = writer.write(model(components));

        assertEquals(695, components.size());
        CycloneDxSchemaValidator.assertValid(json);
    }

    private static SbomModel model(List<SbomComponent> components) {
        SbomComponent root = new SbomComponent(
                SbomComponentType.APPLICATION,
                "urn:zolt:test:spdx-catalog",
                "sh.zolt",
                "spdx-catalog",
                "3.28.0",
                "",
                SbomComponentScope.REQUIRED,
                List.of(),
                List.of());
        return new SbomModel(
                "urn:uuid:2d0e5060-6890-44cd-8ec8-6c6a4ecdf980",
                Optional.empty(),
                List.of(new SbomTool("zolt", TOOL_VERSION)),
                root,
                components,
                List.of());
    }

    private static SbomComponent catalogComponent(String id) {
        return new SbomComponent(
                SbomComponentType.LIBRARY,
                "urn:zolt:test:spdx:" + id,
                "",
                id,
                "",
                "",
                SbomComponentScope.REQUIRED,
                List.of(),
                List.of(SbomLicense.spdx(id)));
    }
}
