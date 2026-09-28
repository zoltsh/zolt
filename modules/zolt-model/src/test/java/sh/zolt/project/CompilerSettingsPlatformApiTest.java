package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class CompilerSettingsPlatformApiTest {
    @Test
    void defaultsUseReproducibleReleaseMode() {
        CompilerSettings settings = CompilerSettings.defaults();

        assertEquals(CompilerSettings.PLATFORM_API_RELEASE, settings.platformApi());
        assertEquals("", settings.testPlatformApi());
        assertEquals("", settings.groovyVersion());
        assertEquals("", settings.kotlinVersion());
        assertFalse(settings.mainHostPlatformApi());
        assertFalse(settings.testHostPlatformApi());
    }

    @Test
    void legacySixArgConstructorKeepsReleaseDefault() {
        CompilerSettings settings = new CompilerSettings(
                "gen", "gentest", "8", "UTF-8", List.of(), List.of());

        assertEquals(CompilerSettings.PLATFORM_API_RELEASE, settings.platformApi());
        assertEquals("", settings.groovyVersion());
        assertEquals("", settings.kotlinVersion());
        assertFalse(settings.mainHostPlatformApi());
    }

    @Test
    void explicitGroovyVersionDoesNotChangeLegacyJavaOnlyEquality() {
        CompilerSettings legacy = new CompilerSettings(
                "gen", "gentest", "21", "UTF-8", List.of("-parameters"), List.of(), "host", "");
        CompilerSettings explicitBlank = new CompilerSettings(
                "gen",
                "gentest",
                "21",
                "UTF-8",
                List.of("-parameters"),
                List.of(),
                "host",
                "",
                " ");
        CompilerSettings groovy = new CompilerSettings(
                "gen",
                "gentest",
                "21",
                "UTF-8",
                List.of("-parameters"),
                List.of(),
                "host",
                "",
                "4.0.22");

        assertEquals(legacy, explicitBlank);
        assertEquals("", legacy.groovyVersion());
        assertEquals("4.0.22", groovy.groovyVersion());
        assertEquals("", groovy.kotlinVersion());
    }

    @Test
    void explicitKotlinVersionPreservesGroovyEraConstructorDefaults() {
        CompilerSettings groovyEra = new CompilerSettings(
                "gen", "gentest", "21", "UTF-8", List.of(), List.of(), "release", "", "4.0.22");
        CompilerSettings kotlin = new CompilerSettings(
                "gen",
                "gentest",
                "21",
                "UTF-8",
                List.of(),
                List.of(),
                "release",
                "",
                "4.0.22",
                "2.2.0");

        assertEquals("", groovyEra.kotlinVersion());
        assertEquals("4.0.22", kotlin.groovyVersion());
        assertEquals("2.2.0", kotlin.kotlinVersion());
    }

    @Test
    void blankPlatformApiFallsBackToReleaseDefault() {
        CompilerSettings settings = new CompilerSettings(
                "gen", "gentest", "8", "", List.of(), List.of(), "", "");

        assertEquals(CompilerSettings.PLATFORM_API_RELEASE, settings.platformApi());
    }

    @Test
    void testPlatformApiInheritsMainWhenBlank() {
        CompilerSettings settings = new CompilerSettings(
                "gen", "gentest", "8", "", List.of(), List.of(), "host", "");

        assertEquals("host", settings.effectiveTestPlatformApi());
        assertTrue(settings.mainHostPlatformApi());
        assertTrue(settings.testHostPlatformApi());
    }

    @Test
    void testPlatformApiOverridesMain() {
        CompilerSettings settings = new CompilerSettings(
                "gen", "gentest", "8", "", List.of(), List.of(), "release", "host");

        assertEquals("host", settings.effectiveTestPlatformApi());
        assertFalse(settings.mainHostPlatformApi());
        assertTrue(settings.testHostPlatformApi());
    }
}
