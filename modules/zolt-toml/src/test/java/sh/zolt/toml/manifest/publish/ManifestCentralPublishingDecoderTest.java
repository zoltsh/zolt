package sh.zolt.toml.manifest.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodeCentral;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodeCentralWithNullIndex;
import static sh.zolt.toml.manifest.ManifestPublishingTestSupport.decodeCentralWithNullObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.toml.ZoltConfigException;

final class ManifestCentralPublishingDecoderTest {
    @Test
    void preservesOmissionWithoutObservingSiblingPublishingDomains() {
        assertTrue(decodeCentral("").isEmpty());
        ArrayList<AuthoredCentralPublishing> observed = new ArrayList<>();
        assertTrue(decodeCentral(
                "publish.signing.method = \"gpg\"\n", observed::add).isEmpty());
        assertTrue(observed.isEmpty());
    }

    @Test
    void decodesAllFieldsAfterObservingTheMinimalRequiredSettings() {
        ArrayList<AuthoredCentralPublishing> observed = new ArrayList<>();
        AuthoredCentralPublishing central = decodeCentral("""
                [publish.central]
                url = "https://central.example.com/api/"
                name = "Zolt Release"
                mode = "automatic"
                tokenEnv = "CENTRAL_TOKEN"
                """, observed::add).orElseThrow();

        assertEquals(new EnvironmentVariableName("CENTRAL_TOKEN"), central.tokenEnvironment());
        assertEquals(AuthoredCentralPublishing.Mode.AUTOMATIC, central.mode());
        assertEquals(Optional.of("Zolt Release"), central.name());
        assertEquals("https://central.example.com/api/", central.url().orElseThrow().value());
        assertEquals(1, observed.size());
        assertEquals(AuthoredCentralPublishing.Mode.AUTOMATIC, observed.getFirst().mode());
        assertTrue(observed.getFirst().name().isEmpty());
        assertTrue(observed.getFirst().url().isEmpty());
    }

    @Test
    void retainsBothMinimalModesWithoutMaterializingAdvancedDefaults() {
        for (AuthoredCentralPublishing.Mode expected : AuthoredCentralPublishing.Mode.values()) {
            AuthoredCentralPublishing central = decodeCentral("""
                    [publish.central]
                    tokenEnv = "CENTRAL_TOKEN"
                    mode = "%s"
                    """.formatted(expected.configValue())).orElseThrow();
            assertEquals(expected, central.mode());
            assertTrue(central.name().isEmpty());
            assertTrue(central.url().isEmpty());
        }
    }

    @Test
    void requiresTokenThenModeBeforeOptionalSemanticValidation() {
        assertRequiredFailure(
                "publish.central.mode = \"manual\"\n",
                "publish.central.tokenEnv");
        assertRequiredFailure("""
                [publish.central]
                tokenEnv = "CENTRAL_TOKEN"
                name = " "
                """, "publish.central.mode");
    }

    @Test
    void anchorsNameBeforeUrlDespiteReverseAssignmentOrder() {
        assertNameFailure(" ", "must not be blank");
        assertNameFailure("name\\t", "must not contain NUL or control characters");
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeCentral("""
                        [publish.central]
                        url = "relative"
                        name = " "
                        mode = "manual"
                        tokenEnv = "CENTRAL_TOKEN"
                        """));
        assertSemanticFailure(failure, "publish.central.name", "must not be blank");
    }

    @Test
    void anchorsUnsafeUrlAndObserverFailuresToTheirCausalFields() {
        ZoltConfigException url = assertThrows(
                ZoltConfigException.class,
                () -> decodeCentral("""
                        [publish.central]
                        tokenEnv = "CENTRAL_TOKEN"
                        mode = "manual"
                        url = "relative"
                        """));
        assertSemanticFailure(url, "publish.central.url", "Invalid repository URL");

        ZoltConfigException observer = assertThrows(
                ZoltConfigException.class,
                () -> decodeCentral("""
                        [publish.central]
                        tokenEnv = "CENTRAL_TOKEN"
                        mode = "manual"
                        name = " "
                        """, ignored -> {
                            throw new IllegalArgumentException("Observed Central conflict.");
                        }));
        assertSemanticFailure(observer, "publish.central.tokenEnv", "Observed Central conflict");
    }

    @Test
    void leavesEmptyTablesSymbolsEnvironmentNamesKindsAndLegacyFieldsToShapeValidation() {
        for (String source : List.of(
                "[publish.central]\n",
                "publish.central.tokenEnv = \"bad-name\"\npublish.central.mode = \"manual\"\n",
                "publish.central.tokenEnv = \"TOKEN\"\npublish.central.mode = \"staging\"\n",
                "publish.central.tokenEnv = 42\npublish.central.mode = \"manual\"\n",
                "publish.central.tokenEnv = \"TOKEN\"\npublish.central.mode = \"manual\"\npublish.central.baseUrl = \"https://central.example.com\"\n",
                "publish.central.tokenEnv = \"TOKEN\"\npublish.central.mode = \"manual\"\npublish.central.publishingType = \"user-managed\"\n")) {
            ZoltConfigException failure = assertThrows(
                    ZoltConfigException.class,
                    () -> decodeCentral(source));
            assertNull(failure.getCause());
        }
    }

    @Test
    void requiresNonNullInputs() {
        assertThrows(NullPointerException.class, () -> decodeCentralWithNullIndex());
        assertThrows(
                NullPointerException.class,
                () -> decodeCentralWithNullObserver("publish.central.tokenEnv = \"TOKEN\"\n"));
    }

    private static void assertNameFailure(String tomlValue, String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeCentral("""
                        [publish.central]
                        tokenEnv = "CENTRAL_TOKEN"
                        mode = "manual"
                        name = "%s"
                        """.formatted(tomlValue)));
        assertSemanticFailure(failure, "publish.central.name", detail);
    }

    private static void assertRequiredFailure(String source, String path) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeCentral(source));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void assertSemanticFailure(
            ZoltConfigException failure,
            String path,
            String detail) {
        assertTrue(failure.getMessage().contains("`" + path + "`"), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }
}
