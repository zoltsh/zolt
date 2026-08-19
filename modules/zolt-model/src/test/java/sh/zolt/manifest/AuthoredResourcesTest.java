package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredResourcesTest {
    @Test
    void retainsTypedTokenSourcesAndCanonicalCollectionOrder() {
        ArrayList<ManifestRelativePath> roots = new ArrayList<>(List.of(
                path("src/z/resources"), path("src/a/resources")));
        LinkedHashMap<LocalId, AuthoredResources.Token> tokens = new LinkedHashMap<>();
        tokens.put(new LocalId("project-version"),
                new AuthoredResources.Token.Project(AuthoredResources.ProjectField.VERSION));
        tokens.put(new LocalId("build-id"),
                new AuthoredResources.Token.Environment(new EnvironmentVariableName("BUILD_ID")));
        tokens.put(new LocalId("channel"), new AuthoredResources.Token.Literal(""));
        AuthoredResources.Filter filter = new AuthoredResources.Filter(
                Optional.empty(),
                List.of(new ResourceGlob("**/*.yaml"), new ResourceGlob("**/*.properties")),
                Optional.of(AuthoredResources.MissingTokenPolicy.KEEP));

        AuthoredResources resources = new AuthoredResources(
                roots, List.of(), Optional.of(filter), tokens);
        roots.clear();
        tokens.clear();

        assertEquals(
                List.of(path("src/a/resources"), path("src/z/resources")), resources.main());
        assertEquals(
                List.of("build-id", "channel", "project-version"),
                resources.tokens().keySet().stream().map(LocalId::value).toList());
        assertEquals(
                List.of("**/*.properties", "**/*.yaml"),
                resources.filter().orElseThrow().include().stream().map(ResourceGlob::value).toList());
        assertEquals(Optional.empty(), resources.filter().orElseThrow().targets());
        assertThrows(UnsupportedOperationException.class, () -> resources.tokens().clear());
    }

    @Test
    void validatesFilterPresenceAndEnvironmentPortability() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredResources.Filter(
                Optional.empty(), List.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredResources.Filter(
                Optional.of(List.of()), List.of(new ResourceGlob("**/*.txt")), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredResources.Filter(
                Optional.of(List.of(AuthoredResources.Target.MAIN, AuthoredResources.Target.MAIN)),
                List.of(new ResourceGlob("**/*.txt")), Optional.empty()));

        Map<LocalId, AuthoredResources.Token> tokens = Map.of(
                new LocalId("upper"),
                new AuthoredResources.Token.Environment(new EnvironmentVariableName("BUILD_ID")),
                new LocalId("lower"),
                new AuthoredResources.Token.Environment(new EnvironmentVariableName("build_id")));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredResources(
                List.of(), List.of(), Optional.empty(), tokens));
        assertThrows(IllegalArgumentException.class, () ->
                new AuthoredResources.Token.Literal("before\0after"));
        assertEquals("line one\nline two", new AuthoredResources.Token.Literal(
                "line one\nline two").value());
    }

    @Test
    void allowsAnEmptySemanticValueForAnAuthoredEmptyTokenCollection() {
        assertEquals(AuthoredResources.empty(),
                new AuthoredResources(List.of(), List.of(), Optional.empty(), Map.of()));
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }
}
