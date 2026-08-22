package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestSchema;

final class ManifestPackageDecoderTest {
    private final ManifestPackageDecoder decoder = new ManifestPackageDecoder();

    @Test
    void preservesOmissionAndExplicitFalseWithoutDefaults() {
        assertTrue(decode("").isEmpty());

        AuthoredPackage settings = decode("package.sources = false\n").orElseThrow();
        assertTrue(settings.mode().isEmpty());
        assertEquals(Optional.of(false), settings.sources());
        assertTrue(settings.javadoc().isEmpty());
        assertTrue(settings.testJar().isEmpty());
        assertTrue(settings.duplicates().isEmpty());
    }

    @Test
    void decodesAllFiveFieldsAndRetainsExplicitDefaults() {
        AuthoredPackage settings = decode("""
                [package]
                mode = "uber-jar"
                sources = false
                javadoc = true
                testJar = false
                duplicates = "fail"
                """).orElseThrow();

        assertEquals(AuthoredPackage.Mode.UBER_JAR, settings.mode().orElseThrow());
        assertFalse(settings.sources().orElseThrow());
        assertTrue(settings.javadoc().orElseThrow());
        assertFalse(settings.testJar().orElseThrow());
        assertEquals(
                AuthoredPackage.DuplicatePolicy.FAIL,
                settings.duplicates().orElseThrow());

        AuthoredPackage explicitJar = decode("package.mode = \"jar\"\n").orElseThrow();
        assertEquals(AuthoredPackage.Mode.JAR, explicitJar.mode().orElseThrow());
    }

    @Test
    void mapsEverySchemaSymbolToTheExactModelEnums() {
        for (AuthoredPackage.Mode mode : AuthoredPackage.Mode.values()) {
            assertEquals(
                    mode,
                    decode("package.mode = \"" + mode.configValue() + "\"\n")
                            .orElseThrow()
                            .mode()
                            .orElseThrow());
        }
        for (AuthoredPackage.DuplicatePolicy policy
                : AuthoredPackage.DuplicatePolicy.values()) {
            assertEquals(
                    policy,
                    decode("""
                            [package]
                            mode = "uber-jar"
                            duplicates = "%s"
                            """.formatted(policy.configValue()))
                            .orElseThrow()
                            .duplicates()
                            .orElseThrow());
        }

        assertEquals(
                symbols(FinalManifestPackagingFields.PACKAGE_MODE),
                Set.copyOf(Arrays.stream(AuthoredPackage.Mode.values())
                        .map(AuthoredPackage.Mode::configValue)
                        .toList()));
        assertEquals(
                symbols(FinalManifestPackagingFields.PACKAGE_DUPLICATES),
                Set.copyOf(Arrays.stream(AuthoredPackage.DuplicatePolicy.values())
                        .map(AuthoredPackage.DuplicatePolicy::configValue)
                        .toList()));
    }

    @Test
    void anchorsDuplicatePolicyCompatibilityToTheDuplicatesField() {
        for (String source : List.of(
                "package.duplicates = \"first-wins\"\n",
                """
                [package]
                duplicates = "fail"
                mode = "jar"
                """)) {
            ZoltConfigException failure = assertSemanticFailure(
                    source,
                    "`package.duplicates`");
            assertTrue(failure.getMessage().contains(
                    "Package duplicates are valid only with mode `uber-jar`."));
        }
    }

    @Test
    void leavesEmptyTablesInvalidSymbolsAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[package]\n",
                "Manifest table `[package]` must not be empty");
        assertShapeFailure(
                "package.mode = \"bom\"\n",
                "Invalid symbol `bom` for `package.mode`");
        assertShapeFailure(
                "package.sources = \"true\"\n",
                "expected boolean but found string");
    }

    /**
     * Design §12.1: {@code bom} is internal packaging implied by a BOM domain, so it names the wrong
     * domain rather than mistyping a mode. Only that value earns the domain hint.
     */
    @Test
    void modeBomSuggestsTheBomDomainWhileOtherInvalidSymbolsDoNot() {
        assertShapeFailure(
                "package.mode = \"bom\"\n",
                "Author a [bom] domain instead; BOM packaging has no package.mode spelling.");
        ZoltConfigException other = assertThrows(
                ZoltConfigException.class,
                () -> decode("package.mode = \"zip\"\n"));
        assertTrue(other.getMessage().contains("Invalid symbol `zip` for `package.mode`"), other.getMessage());
        assertFalse(other.getMessage().contains("[bom] domain"), other.getMessage());
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private Optional<AuthoredPackage> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private ZoltConfigException assertSemanticFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }

    private static Set<String> symbols(sh.zolt.toml.schema.ManifestField field) {
        String family = field.symbolFamily().orElseThrow();
        return Set.copyOf(FinalManifestSchema.registry()
                .symbols()
                .family(family)
                .orElseThrow()
                .values());
    }
}
