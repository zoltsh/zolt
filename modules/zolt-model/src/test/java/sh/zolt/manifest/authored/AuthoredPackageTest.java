package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredPackageTest {
    @Test
    void modelsOnlyTheFinalAuthoredPackageModeVocabulary() {
        assertEquals(
                List.of("jar", "uber-jar", "war", "spring-boot", "spring-boot-war", "quarkus"),
                java.util.Arrays.stream(AuthoredPackage.Mode.values())
                        .map(AuthoredPackage.Mode::configValue)
                        .toList());
        assertEquals(
                AuthoredPackage.Mode.UBER_JAR,
                AuthoredPackage.Mode.fromConfigValue("uber-jar"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthoredPackage.Mode.fromConfigValue("bom"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AuthoredPackage.Mode.fromConfigValue("uber"));
    }

    @Test
    void preservesExplicitBooleanDefaultsAndUberDuplicatePolicy() {
        AuthoredPackage settings = new AuthoredPackage(
                Optional.of(AuthoredPackage.Mode.UBER_JAR),
                Optional.of(false),
                Optional.of(true),
                Optional.empty(),
                Optional.of(AuthoredPackage.DuplicatePolicy.FIRST_WINS));

        assertFalse(settings.sources().orElseThrow());
        assertEquals(AuthoredPackage.DuplicatePolicy.FIRST_WINS, settings.duplicates().orElseThrow());
        assertEquals(
                AuthoredPackage.DuplicatePolicy.FAIL,
                AuthoredPackage.DuplicatePolicy.fromConfigValue("fail"));
    }

    @Test
    void rejectsEmptyPackageSettingsAndDuplicatesOutsideUberJar() {
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        Optional.empty(),
                        Optional.of(AuthoredPackage.DuplicatePolicy.FAIL)));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        Optional.of(AuthoredPackage.Mode.JAR),
                        Optional.of(AuthoredPackage.DuplicatePolicy.FIRST_WINS)));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(
                        Optional.of(AuthoredPackage.Mode.SPRING_BOOT),
                        Optional.of(AuthoredPackage.DuplicatePolicy.FAIL)));
    }

    @Test
    void sortsAndDefensivelyCopiesManifestAttributesWhilePreservingSpelling() {
        LinkedHashMap<String, String> source = new LinkedHashMap<>();
        source.put("X.Vendor-Flag", "");
        source.put("Automatic-Module-Name", "Com.Example.Library");

        AuthoredPackageManifest manifest = new AuthoredPackageManifest(source);
        source.clear();

        assertEquals(
                List.of("Automatic-Module-Name", "X.Vendor-Flag"),
                new ArrayList<>(manifest.attributes().keySet()));
        assertEquals("Com.Example.Library", manifest.attributes().get("Automatic-Module-Name"));
        assertEquals("", manifest.attributes().get("X.Vendor-Flag"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.attributes().clear());
    }

    @Test
    void preservesAnExplicitEmptyManifestCollectionButRejectsBlankKeys() {
        assertEquals(Map.of(), new AuthoredPackageManifest(Map.of()).attributes());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredPackageManifest(Map.of(" ", "value")));
    }

    private static AuthoredPackage settings(
            Optional<AuthoredPackage.Mode> mode,
            Optional<AuthoredPackage.DuplicatePolicy> duplicates) {
        return new AuthoredPackage(
                mode,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                duplicates);
    }
}
