package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class DependencySelectorTest {
    @Test
    void representsExactlyTheFourSemanticSelectorForms() {
        DependencySelector.FixedVersion fixed = new DependencySelector.FixedVersion("2.0.17");
        DependencySelector.VersionReference reference =
                new DependencySelector.VersionReference(new LocalId("slf4j"));

        assertEquals("2.0.17", fixed.value());
        assertEquals("slf4j", reference.alias().value());
        assertInstanceOf(DependencySelector.Managed.class, new DependencySelector.Managed());
        assertInstanceOf(DependencySelector.Workspace.class, new DependencySelector.Workspace());
        assertEquals(
                4,
                DependencySelector.class.getPermittedSubclasses().length,
                "Adding a selector is a manifest-language contract change.");
    }

    @Test
    void fixedVersionsUseTheStrictExternalDependencyPolicy() {
        for (String value : List.of(
                "",
                " 1.0.0",
                "[1.0,2.0)",
                "latest.release",
                "1.+",
                "${release}",
                "1.0.")) {
            assertThrows(IllegalArgumentException.class, () -> new DependencySelector.FixedVersion(value), value);
        }
    }

    @Test
    void defersSnapshotAvailabilityToEnvironmentAwareResolution() {
        DependencySelector.FixedVersion selector =
                new DependencySelector.FixedVersion("1.0.0-SNAPSHOT");

        assertEquals("1.0.0-SNAPSHOT", selector.value());
    }

    @Test
    void versionReferencesUseTheFinalLocalIdGrammar() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DependencySelector.VersionReference(new LocalId("SpringBoot")));
        assertThrows(
                NullPointerException.class,
                () -> new DependencySelector.VersionReference(null));
    }
}
