package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PackageSettingsTest {
    @Test
    void modeOverridePreservesTheRemainingContract() {
        PublicationMetadata metadata = new PublicationMetadata(
                "Demo",
                "Description",
                "https://example.test",
                "Apache-2.0",
                "",
                List.of("Zolt"),
                List.of(),
                "",
                "",
                "",
                "",
                "");
        BomSettings bom = new BomSettings(
                new BomSettings.Members(false, List.of("modules/api"), List.of()),
                List.of(),
                List.of());
        PackageSettings settings = new PackageSettings(
                PackageMode.THIN,
                true,
                true,
                true,
                metadata,
                Map.of("Automatic-Module-Name", "com.example.demo"),
                UberDuplicatePolicy.FIRST_WINS,
                bom);

        PackageSettings overridden = settings.withMode(PackageMode.UBER);

        assertEquals(PackageMode.UBER, overridden.mode());
        assertTrue(overridden.sources());
        assertTrue(overridden.javadoc());
        assertTrue(overridden.tests());
        assertEquals(metadata, overridden.metadata());
        assertEquals(settings.manifestAttributes(), overridden.manifestAttributes());
        assertEquals(UberDuplicatePolicy.FIRST_WINS, overridden.uberDuplicates());
        assertEquals(bom, overridden.bom());
    }
}
