package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.GeneratedProcessBinary;
import sh.zolt.manifest.GeneratedVersionExpectation;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredGeneratedPresets;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredGeneratedTools;
import sh.zolt.manifest.authored.AuthoredOpenApiOptions;

final class ManifestGeneratedToolsPresetsWriterTest {
    @Test
    void emitsEveryTypedToolAndPresetFieldInCanonicalOrder() {
        AuthoredGeneratedSources generated = new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(
                        id("process"), new AuthoredGeneratedTool.Process(
                                new GeneratedProcessBinary("npm"),
                                List.of("npm", "--version"),
                                Optional.of(new GeneratedVersionExpectation(">=10 <11")),
                                true),
                        id("openapi"), new AuthoredGeneratedTool.OpenApi(
                                Optional.of(coordinate("org.openapitools:openapi-generator-cli")),
                                Optional.of(reference("openapi-version"))),
                        id("protobuf"), new AuthoredGeneratedTool.Protobuf(
                                Optional.of(coordinate("com.google.protobuf:protoc")),
                                Optional.of(fixed("4.30.0")),
                                Optional.of(coordinate("io.grpc:protoc-gen-grpc-java")),
                                Optional.of(reference("grpc-version"))),
                        id("jvm"), new AuthoredGeneratedTool.Jvm(
                                List.of(
                                        request("org.example:runner", fixed("1.2.3")),
                                        request("org.example:helper", reference("helper-version"))),
                                new JavaBinaryClassName("org.example.codegen.Main")),
                        id("custom-openapi"), new AuthoredGeneratedTool.OpenApi(
                                Optional.of(coordinate("org.example:openapi")),
                                Optional.of(fixed("2.0.0"))),
                        id("custom-protobuf"), new AuthoredGeneratedTool.Protobuf(
                                Optional.empty(),
                                Optional.of(reference("protoc-version")),
                                Optional.empty(),
                                Optional.of(fixed("1.70.0"))))),
                new AuthoredGeneratedPresets(Map.of(
                        id("z-full"), options(),
                        id("a-minimal"), AuthoredOpenApiOptions.empty())),
                Map.of(),
                Map.of());

        String output = write(Optional.of(generated));

        assertEquals(
                """
                [generated.tools.custom-openapi]
                kind = "openapi"
                coordinate = "org.example:openapi"
                version = "2.0.0"

                [generated.tools.custom-protobuf]
                kind = "protobuf"
                protocVersionRef = "protoc-version"
                grpcVersion = "1.70.0"

                [generated.tools.jvm]
                kind = "jvm"
                coordinates = [
                    { coordinate = "org.example:runner", version = "1.2.3" },
                    { coordinate = "org.example:helper", versionRef = "helper-version" },
                ]
                mainClass = "org.example.codegen.Main"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "openapi-version"

                [generated.tools.process]
                kind = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                versionExpect = ">=10 <11"
                allowUnpinnedTool = true

                [generated.tools.protobuf]
                protocCoordinate = "com.google.protobuf:protoc"
                protocVersion = "4.30.0"
                grpcCoordinate = "io.grpc:protoc-gen-grpc-java"
                grpcVersionRef = "grpc-version"

                [generated.presets.a-minimal]
                kind = "openapi"

                [generated.presets.z-full]
                kind = "openapi"
                generator = "java"
                library = "webclient"
                apiPackage = "org.example.api"
                modelPackage = "org.example.model"
                invokerPackage = "org.example.invoker"
                config = "openapi/config.json"
                templateDir = "openapi/templates"
                validateSpec = false
                options = { "Case.Key" = "line one\\nline two", zKey = "", "" = "bmp", "𐀀" = "supplementary" }
                additionalProperties = { hideGenerationTimestamp = "true" }
                configOptions = { useJakartaEe = "true" }
                globalProperties = { models = "" }
                typeMappings = { OffsetDateTime = "Instant" }
                importMappings = { Instant = "java.time.Instant" }
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertEquals(generated, decode(output));
    }

    @Test
    void omitsEmptyDomainsAndFrozenTrueDefaultsWithoutEmptyInlineTables() {
        AuthoredOpenApiOptions defaulted = new AuthoredOpenApiOptions(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(true),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        AuthoredGeneratedSources generated = new AuthoredGeneratedSources(
                new AuthoredGeneratedTools(Map.of(
                        id("openapi"), new AuthoredGeneratedTool.OpenApi(
                                Optional.empty(), Optional.empty()))),
                new AuthoredGeneratedPresets(Map.of(id("minimal"), defaulted)),
                Map.of(),
                Map.of());

        String output = write(Optional.of(generated));

        assertEquals(
                """
                [generated.presets.minimal]
                kind = "openapi"
                """,
                output);
        assertFalse(output.contains("{}"));
        assertEquals("", write(Optional.empty()));
        assertEquals("", write(Optional.of(AuthoredGeneratedSources.empty())));
        assertEquals(
                AuthoredOpenApiOptions.empty(),
                decode(output).presets().openApi().get(id("minimal")));
    }

    private static AuthoredOpenApiOptions options() {
        return new AuthoredOpenApiOptions(
                Optional.of("java"),
                Optional.of("webclient"),
                Optional.of("org.example.api"),
                Optional.of("org.example.model"),
                Optional.of("org.example.invoker"),
                Optional.of(path("openapi/config.json")),
                Optional.of(path("openapi/templates")),
                Optional.of(false),
                Map.of(
                        "zKey", "",
                        "Case.Key", "line one\nline two",
                        "\uE000", "bmp",
                        "\uD800\uDC00", "supplementary"),
                Map.of("hideGenerationTimestamp", "true"),
                Map.of("useJakartaEe", "true"),
                Map.of("models", ""),
                Map.of("OffsetDateTime", "Instant"),
                Map.of("Instant", "java.time.Instant"));
    }

    private static AuthoredGeneratedSources decode(String output) {
        return decodeAuthoredManifest(
                        "[project]\nname = \"round-trip\"\n\n" + output)
                .generated()
                .orElseThrow();
    }

    private static String write(Optional<AuthoredGeneratedSources> generated) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestGeneratedSourcesWriter().write(emitter, generated);
        return emitter.finish();
    }

    private static GeneratedArtifactRequest request(
            String coordinate, DependencySelector selector) {
        return new GeneratedArtifactRequest(coordinate(coordinate), selector);
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }

    private static DependencySelector fixed(String value) {
        return new DependencySelector.FixedVersion(value);
    }

    private static DependencySelector reference(String value) {
        return new DependencySelector.VersionReference(id(value));
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
