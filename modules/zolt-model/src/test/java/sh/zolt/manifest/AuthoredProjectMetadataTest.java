package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredProjectMetadataTest {
    @Test
    void retainsProjectLocalMetadataAndSortsDevelopersByLocalId() {
        LinkedHashMap<LocalId, AuthoredProjectDeveloper> developers = new LinkedHashMap<>();
        developers.put(
                new LocalId("grace"),
                developer(Optional.of("Grace Hopper"), Optional.empty()));
        developers.put(
                new LocalId("ada"),
                developer(Optional.of("Ada Lovelace"), Optional.of("ada@example.com")));

        AuthoredProjectMetadata metadata = new AuthoredProjectMetadata(
                Optional.of(new JavaBinaryClassName("com.example.Main")),
                Optional.of("  A reusable Java library.  "),
                Optional.of("https://EXAMPLE.com/Library/"),
                Optional.of("https://github.com/example/library/issues"),
                Optional.of(new AuthoredProjectScm(
                        Optional.of("https://github.com/example/library"),
                        Optional.of("scm:git:https://github.com/example/library.git"),
                        Optional.of("scm:git:ssh://git@github.com/example/library.git"),
                        Optional.of("v1.0.0"))),
                developers);
        developers.clear();

        assertEquals("com.example.Main", metadata.main().orElseThrow().value());
        assertEquals("  A reusable Java library.  ", metadata.description().orElseThrow());
        assertEquals("https://EXAMPLE.com/Library/", metadata.url().orElseThrow());
        assertEquals(
                List.of(new LocalId("ada"), new LocalId("grace")),
                new ArrayList<>(metadata.developers().keySet()));
        assertEquals(
                "scm:git:ssh://git@github.com/example/library.git",
                metadata.scm().orElseThrow().developerConnection().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> metadata.developers().clear());
    }

    @Test
    void representsAnIdentityTogetherWithEmptyLocalMetadata() {
        AuthoredProjectIdentity identity = new AuthoredProjectIdentity(
                new ProjectName("orders-core"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        AuthoredProject project = new AuthoredProject(identity, AuthoredProjectMetadata.empty());

        assertEquals("orders-core", project.identity().name().value());
        assertEquals(AuthoredProjectMetadata.empty(), project.metadata());
    }

    @Test
    void rejectsBlankProjectMetadataWhenAuthored() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredProjectMetadata(
                        Optional.empty(),
                        Optional.of("  "),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredProjectMetadata(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("\t"),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of()));
    }

    private static AuthoredProjectDeveloper developer(
            Optional<String> name,
            Optional<String> email) {
        return new AuthoredProjectDeveloper(
                name,
                email,
                Optional.empty(),
                Optional.empty());
    }
}
