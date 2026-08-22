package sh.zolt.build.generatedsource;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class OpenApiGeneratedSourceServiceTestSupport {
    private OpenApiGeneratedSourceServiceTestSupport() {}

    static void writeProjectFiles(Path projectDir) throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/openapi"));
        Files.writeString(projectDir.resolve("src/main/openapi/public-api.yaml"), "openapi: 3.1.0\n");
        Files.createDirectories(projectDir.resolve("cache/org/openapitools/openapi-generator-cli/7.11.0"));
        Files.writeString(
                projectDir.resolve("cache/org/openapitools/openapi-generator-cli/7.11.0/openapi-generator-cli-7.11.0.jar"),
                "tool\n");
        Files.createDirectories(projectDir.resolve("cache/com/example/app/1.0.0"));
        Files.writeString(projectDir.resolve("cache/com/example/app/1.0.0/app-1.0.0.jar"), "app\n");
    }

    static void writeGeneratedInterface(Path output) throws IOException {
        Files.createDirectories(output.resolve("com/example/generated"));
        Files.writeString(output.resolve("com/example/generated/PublicApi.java"), """
                package com.example.generated;

                public interface PublicApi {
                }
                """);
    }

    static OpenApiGeneratedSourceService service(Path projectDir, OpenApiGeneratedSourceService.ProcessRunner runner) {
        return new OpenApiGeneratedSourceService(
                requiredVersion -> new JdkStatus(
                        Optional.empty(),
                        Optional.of(projectDir.resolve("fake-java")),
                        Optional.of(Path.of("javac")),
                        Optional.of(Path.of("jar")),
                        Optional.of(requiredVersion),
                        requiredVersion),
                ":",
                runner);
    }

    static ProjectConfig configWithAdditionalProperties(Map<String, String> additionalProperties) {
        StringBuilder properties = new StringBuilder();
        additionalProperties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!properties.isEmpty()) {
                        properties.append(", ");
                    }
                    properties.append(entry.getKey()).append(" = \"").append(entry.getValue()).append('"');
                });
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                additionalProperties = { %s }
                """.formatted(properties));
    }

    static ProjectConfig multiSpecConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.presets.spring-api]
                kind = "openapi"
                generator = "spring"
                library = "spring-boot"
                apiPackage = "com.example.api"
                modelPackage = "com.example.model"
                validateSpec = false
                additionalProperties = { sourceFolder = ".", interfaceOnly = "true", useSpringBoot3 = "true" }
                configOptions = { additionalModelTypeAnnotations = "@lombok.Builder;@lombok.NoArgsConstructor" }
                globalProperties = { models = "true", apis = "true" }
                typeMappings = { UUID = "String", OffsetDateTime = "Instant" }
                importMappings = { String = "java.lang.String", Instant = "java.time.Instant" }

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                preset = "spring-api"
                apiPackage = "com.example.publicapi"
                modelPackage = "com.example.publicmodel"

                [generated.main.internal-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/internal-api.yaml"
                output = "target/generated/sources/openapi/internal-api"
                preset = "spring-api"
                apiPackage = "com.example.internalapi"
                modelPackage = "com.example.internalmodel"
                """);
    }

    static List<ResolvedClasspathPackage> packages(Path projectDir) {
        return List.of(
                packageWithScope(
                        projectDir,
                        "org.openapitools",
                        "openapi-generator-cli",
                        "7.11.0",
                        DependencyScope.TOOL_OPENAPI,
                        "cache/org/openapitools/openapi-generator-cli/7.11.0/openapi-generator-cli-7.11.0.jar"),
                packageWithScope(
                        projectDir,
                        "com.example",
                        "app",
                        "1.0.0",
                        DependencyScope.COMPILE,
                        "cache/com/example/app/1.0.0/app-1.0.0.jar"));
    }

    private static ResolvedClasspathPackage packageWithScope(
            Path projectDir,
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            String jar) {
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        new PackageId(group, artifact),
                        version,
                        false,
                        Path.of(""),
                        projectDir.resolve(jar)),
                scope);
    }
}
