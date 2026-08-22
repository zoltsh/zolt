package sh.zolt.resolve;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProtobufGenerationSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ResolveGeneratedSourceTestConfigs {
    private ResolveGeneratedSourceTestConfigs() {
    }

    static ProjectConfig processorConfig(URI baseUri) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of("com.example:processor", "1.0.0"),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                null);
    }

    static ProjectConfig openApiConfig(URI baseUri) {
        OpenApiGenerationSettings settings = new OpenApiGenerationSettings(
                Optional.of("org.openapitools:openapi-generator-cli"),
                Optional.of("7.11.0"),
                Optional.empty(),
                Optional.of("spring"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
        GeneratedSourceStep step = new GeneratedSourceStep(
                "public-api",
                GeneratedSourceKind.OPENAPI,
                "java",
                "target/generated/sources/openapi/public-api",
                List.of("src/main/openapi/public-api.yaml"),
                true,
                true,
                settings);
        return ResolveTestConfigs.configWithDependencies(baseUri, Map.of())
                .withBuildSettings(BuildSettings.defaults().withGeneratedSources(List.of(step), List.of()));
    }

    static ProjectConfig openApiVersionRefConfig(URI baseUri, String alias) {
        return new sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories.test]
                url = "%s"

                [versions]
                "%s" = "7.11.0"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                versionRef = "%s"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                """.formatted(baseUri, alias, alias));
    }

    static ProjectConfig protobufConfig(URI baseUri) {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "greeter",
                GeneratedSourceKind.PROTOBUF,
                "java",
                "target/generated/sources/protobuf",
                List.of("src/main/proto/greeter.proto"),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                new ProtobufGenerationSettings(
                        Optional.of("com.google.protobuf:protoc"),
                        Optional.of("4.28.3"),
                        Optional.empty(),
                        Optional.of("io.grpc:protoc-gen-grpc-java"),
                        Optional.of("1.68.1"),
                        Optional.empty(),
                        Optional.empty(),
                        true));
        return ResolveTestConfigs.configWithDependencies(baseUri, Map.of())
                .withBuildSettings(BuildSettings.defaults().withGeneratedSources(List.of(step), List.of()));
    }

    static ProjectConfig configWithDependencyAndProcessor(URI baseUri) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of(),
                Map.of("com.example:app", "1.0.0"),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of("com.example:processor", "1.0.0"),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                null);
    }

    static ProjectConfig processorPlatformConfig(URI baseUri) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of("com.example:platform", "1.0.0"),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of("com.example:processor"),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                null);
    }
}
