package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.authored.AuthoredResources;

final class ManifestResourcesWriterTest {
    @Test
    void emitsCustomRootsFilterAndTypedTokensInCanonicalOrder() {
        AuthoredResources resources = new AuthoredResources(
                List.of(path("src/main/resources"), path("config")),
                List.of(path("fixtures")),
                Optional.of(new AuthoredResources.Filter(
                        Optional.of(List.of(
                                AuthoredResources.Target.TEST,
                                AuthoredResources.Target.MAIN)),
                        List.of(glob("**/*.yaml"), glob("**/*.properties")),
                        Optional.of(AuthoredResources.MissingTokenPolicy.KEEP))),
                Map.of(
                        id("channel"), new AuthoredResources.Token.Literal("preview\nnext"),
                        id("app-version"), new AuthoredResources.Token.Project(
                                AuthoredResources.ProjectField.VERSION),
                        id("build-id"), new AuthoredResources.Token.Environment(
                                new EnvironmentVariableName("BUILD_ID"))));

        String output = write(Optional.of(resources));

        assertEquals(
                """
                [resources]
                main = ["config", "src/main/resources"]
                test = ["fixtures"]

                [resources.filter]
                targets = ["main", "test"]
                include = ["**/*.properties", "**/*.yaml"]
                missing = "keep"

                [resources.tokens]
                app-version = { project = "version" }
                build-id = { env = "BUILD_ID" }
                channel = { value = "preview\\nnext" }
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertEquals(
                resources,
                decodeAuthoredManifest("[project]\nname = \"round-trip\"\n\n" + output)
                        .build()
                        .resources()
                        .orElseThrow());
    }

    @Test
    void omitsConventionalRootsAndEmptyCollections() {
        AuthoredResources conventional = new AuthoredResources(
                List.of(path("src/main/resources")),
                List.of(path("src/test/resources")),
                Optional.empty(),
                Map.of());

        assertEquals("", write(Optional.empty()));
        assertEquals("", write(Optional.of(AuthoredResources.empty())));
        assertEquals("", write(Optional.of(conventional)));
    }

    @Test
    void omitsDefaultFilterValuesButRetainsRequiredIncludes() {
        AuthoredResources resources = new AuthoredResources(
                List.of(),
                List.of(),
                Optional.of(new AuthoredResources.Filter(
                        Optional.of(List.of(AuthoredResources.Target.MAIN)),
                        List.of(glob("**/*.properties")),
                        Optional.of(AuthoredResources.MissingTokenPolicy.FAIL))),
                Map.of());

        String output = write(Optional.of(resources));

        assertEquals(
                """
                [resources.filter]
                include = ["**/*.properties"]
                """,
                output);
        AuthoredResources normalized = decodeAuthoredManifest(
                        "[project]\nname = \"round-trip\"\n\n" + output)
                .build()
                .resources()
                .orElseThrow();
        assertEquals(Optional.empty(), normalized.filter().orElseThrow().targets());
        assertEquals(Optional.empty(), normalized.filter().orElseThrow().missing());
    }

    @Test
    void normalizesAnExplicitEmptyTokenCollectionToOmission() {
        AuthoredResources resources = new AuthoredResources(
                List.of(), List.of(), Optional.empty(), Map.of());

        assertEquals("", write(Optional.of(resources)));
    }

    private static String write(Optional<AuthoredResources> resources) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestResourcesWriter().write(emitter, resources);
        return emitter.finish();
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static ResourceGlob glob(String value) {
        return new ResourceGlob(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
