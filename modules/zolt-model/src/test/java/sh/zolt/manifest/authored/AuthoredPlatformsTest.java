package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;

final class AuthoredPlatformsTest {
    @Test
    void retainsOnlyFixedAndVersionReferenceSelectorsInCoordinateOrder() {
        DependencyCoordinate spring =
                new DependencyCoordinate("org.springframework.boot:spring-boot-dependencies");
        DependencyCoordinate netty = new DependencyCoordinate("io.netty:netty-bom");
        LinkedHashMap<DependencyCoordinate, PlatformSelector> source = new LinkedHashMap<>();
        source.put(spring, new PlatformSelector.VersionReference(new LocalId("spring-boot")));
        source.put(netty, new PlatformSelector.FixedVersion("4.1.119.Final"));

        AuthoredPlatforms platforms = new AuthoredPlatforms(source);
        source.clear();

        assertEquals(List.of(netty, spring), List.copyOf(platforms.entries().keySet()));
        assertInstanceOf(PlatformSelector.FixedVersion.class, platforms.entries().get(netty));
        assertInstanceOf(PlatformSelector.VersionReference.class, platforms.entries().get(spring));
        assertThrows(UnsupportedOperationException.class, () -> platforms.entries().clear());
    }

    @Test
    void fixedPlatformsAcceptDeferredSnapshotsButRejectEveryDynamicSelector() {
        assertEquals("4.0-SNAPSHOT", new PlatformSelector.FixedVersion("4.0-SNAPSHOT").value());

        for (String value : List.of("", "[4.0,5.0)", "4.+", "LATEST", "${spring}", "4.0.")) {
            assertThrows(IllegalArgumentException.class, () -> new PlatformSelector.FixedVersion(value), value);
        }
    }

    @Test
    void versionReferencesAndCoordinatesUseTheirExactFinalGrammars() {
        assertEquals("spring-boot",
                new PlatformSelector.VersionReference(new LocalId("spring-boot")).alias().value());
        assertThrows(IllegalArgumentException.class,
                () -> new PlatformSelector.VersionReference(new LocalId("SpringBoot")));
        assertThrows(IllegalArgumentException.class,
                () -> new DependencyCoordinate("io.netty:netty-bom:tests"));
    }
}
