package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ManifestProvenanceTest {
    private static final ManifestSource MEMBER_NAME =
            new ManifestSource("modules/core/zolt.toml", "project.name");

    @Test
    void identifiesAuthoredInheritedAndBuiltInValues() {
        EffectiveValue<ProjectName> authored =
                EffectiveValue.authored(new ProjectName("core"), MEMBER_NAME);
        EffectiveValue<String> inherited = EffectiveValue.inherited(
                "com.example", new ManifestSource("zolt.toml", "workspace.project.group"));
        EffectiveValue<String> builtIn = EffectiveValue.builtIn("jar");

        assertEquals(ValueOrigin.AUTHORED, authored.origin());
        assertEquals(Optional.of(MEMBER_NAME), authored.source());
        assertEquals(ValueOrigin.INHERITED, inherited.origin());
        assertEquals("workspace.project.group", inherited.source().orElseThrow().fieldPath());
        assertEquals(ValueOrigin.BUILT_IN, builtIn.origin());
        assertEquals(Optional.empty(), builtIn.source());
    }

    @Test
    void mappedValuesRetainTheirProvenance() {
        EffectiveValue<Integer> length = EffectiveValue.authored("core", MEMBER_NAME).map(String::length);

        assertEquals(4, length.value());
        assertEquals(ValueOrigin.AUTHORED, length.origin());
        assertEquals(Optional.of(MEMBER_NAME), length.source());
    }

    @Test
    void rejectsContradictoryValueProvenance() {
        IllegalArgumentException builtInSource = assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveValue<>("jar", ValueOrigin.BUILT_IN, Optional.of(MEMBER_NAME)));
        IllegalArgumentException missingAuthoredSource = assertThrows(
                IllegalArgumentException.class,
                () -> new EffectiveValue<>("core", ValueOrigin.AUTHORED, Optional.empty()));

        assertEquals(
                "A built-in value cannot have an authored manifest source.",
                builtInSource.getMessage());
        assertEquals(
                "An authored or inherited value requires a manifest source.",
                missingAuthoredSource.getMessage());
    }

    @Test
    void acceptsOnlyPortableRelativeManifestSourcePaths() {
        assertEquals("zolt.toml", new ManifestSource("zolt.toml", "project.name").manifestPath());

        for (String invalid : new String[] {
                "", "/tmp/zolt.toml", "../zolt.toml", "modules\\core\\zolt.toml", "C:/zolt.toml"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new ManifestSource(invalid, "project.name"),
                    invalid);
        }
    }
}
