package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ManifestSymbolRegistryTest {
    @Test
    void preservesCanonicalFamilyAndValueOrder() {
        ManifestSymbolRegistry registry = new ManifestSymbolRegistry(List.of(
                new ManifestSymbolFamily("first-family", List.of("first", "second-value")),
                new ManifestSymbolFamily("second-family", List.of("only"))));

        assertEquals(List.of("first-family", "second-family"),
                registry.families().stream().map(ManifestSymbolFamily::name).toList());
        assertEquals(List.of("first", "second-value"),
                registry.family("first-family").orElseThrow().values());
        assertThrows(UnsupportedOperationException.class, () -> registry.families().add(
                new ManifestSymbolFamily("third-family", List.of("value"))));
    }

    @Test
    void rejectsInvalidOrDuplicateSymbolsAndFamilies() {
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestSymbolFamily("bad_family", List.of("value")));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestSymbolFamily("family", List.of("UPPER")));
        assertThrows(IllegalArgumentException.class,
                () -> new ManifestSymbolFamily("family", List.of("same", "same")));

        ManifestSymbolFamily family = new ManifestSymbolFamily("family", List.of("value"));
        assertThrows(IllegalArgumentException.class, () -> new ManifestSymbolRegistry(List.of(family, family)));
    }
}
