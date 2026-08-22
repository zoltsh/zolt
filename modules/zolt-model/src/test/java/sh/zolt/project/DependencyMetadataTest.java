package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class DependencyMetadataTest {
    @Test
    void normalizesBlankOptionalFields() {
        DependencyMetadata metadata = new DependencyMetadata(
                "dependencies",
                "com.example:app",
                " ",
                " ",
                false,
                " ",
                false,
                false,
                null);

        assertEquals("dependencies|com.example:app", DependencyMetadata.key("dependencies", "com.example:app"));
        assertTrue(metadata.emptyMetadata());
        assertEquals(List.of(), metadata.exclusions());
    }

    @Test
    void retainsPublishMetadataAndExclusions() {
        DependencyMetadata metadata = new DependencyMetadata(
                "dependencies",
                "com.example:app",
                "1.0.0",
                null,
                false,
                null,
                true,
                true,
                List.of(new DependencyExclusionSpec("com.example", "legacy")));

        assertFalse(metadata.emptyMetadata());
        assertEquals("com.example:legacy", metadata.exclusions().getFirst().coordinate());
    }

    @Test
    void retainsClassifierAndType() {
        DependencyMetadata metadata = new DependencyMetadata(
                "dependencies",
                "io.netty:netty-transport-native-epoll",
                "4.1.100.Final",
                null,
                false,
                null,
                false,
                false,
                List.of(),
                "linux-x86_64",
                "tar.gz");

        assertFalse(metadata.emptyMetadata());
        assertFalse(metadata.defaultVariant());
        assertEquals("linux-x86_64", metadata.classifier());
        assertEquals("tar.gz", metadata.type());
    }

    /**
     * Design §9.7 makes {@code jar} the default artifact type, so an explicit one is the same variant
     * as none: keeping both spellings would publish two different POMs and two different lock
     * fingerprints for one dependency.
     */
    @Test
    void normalizesTheExplicitDefaultTypeToTheDefaultVariant() {
        DependencyMetadata metadata = new DependencyMetadata(
                "dependencies",
                "com.example:app",
                "1.0.0",
                null,
                false,
                null,
                false,
                false,
                List.of(),
                null,
                DependencyMetadata.DEFAULT_TYPE);

        assertTrue(metadata.emptyMetadata());
        assertTrue(metadata.defaultVariant());
        assertEquals(null, metadata.type());
    }

    @Test
    void normalizesBlankClassifierAndType() {
        DependencyMetadata metadata = new DependencyMetadata(
                "dependencies",
                "com.example:app",
                "1.0.0",
                null,
                false,
                null,
                false,
                false,
                List.of(),
                " ",
                " ");

        assertTrue(metadata.emptyMetadata());
        assertEquals(null, metadata.classifier());
        assertEquals(null, metadata.type());
    }

    @Test
    void classifierAloneKeepsMetadataNonEmpty() {
        DependencyMetadata metadata = new DependencyMetadata(
                "test.dependencies",
                "org.apache.kafka:kafka-clients",
                "3.7.0",
                null,
                false,
                null,
                false,
                false,
                List.of(),
                "test",
                null);

        assertFalse(metadata.emptyMetadata());
        assertEquals("test", metadata.classifier());
        assertEquals(null, metadata.type());
    }

    @Test
    void nineArgConstructorDefaultsClassifierAndType() {
        DependencyMetadata metadata = new DependencyMetadata(
                "dependencies",
                "com.example:app",
                "1.0.0",
                null,
                false,
                null,
                false,
                false,
                List.of());

        assertEquals(null, metadata.classifier());
        assertEquals(null, metadata.type());
        assertTrue(metadata.emptyMetadata());
    }

    @Test
    void requiresSectionAndCoordinate() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyMetadata(null, "com.example:app", null, false, null, false, false, List.of()));

        assertEquals("Dependency metadata section and coordinate are required.", exception.getMessage());
    }
}
