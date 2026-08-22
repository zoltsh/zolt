package sh.zolt.resolve.support;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.QuarkusPackageMode;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.project.SpringBootSettings;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class ResolvePackagingTestConfigs {
    private ResolvePackagingTestConfigs() {
    }

    static ProjectConfig platformConfig(URI baseUri) {
        return ProjectConfigs.withRuntimeDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of("com.example:platform", "1.0.0"),
                Map.of(),
                Set.of("com.example:app"),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                null);
    }

    static ProjectConfig platformVersionRefConfig(URI baseUri, String alias) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [versions]
                "%s" = "1.0.0"

                [platforms]
                "com.example:platform" = { versionRef = "%s" }

                [dependencies]
                "com.example:app" = { managed = true }
                """.formatted(baseUri, alias, alias));
    }

    static ProjectConfig testPlatformConfig(URI baseUri) {
        return ProjectConfigs.withRuntimeDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of("com.example:platform", "1.0.0"),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of("com.example:app"),
                BuildSettings.defaults(),
                null);
    }

    static ProjectConfig runtimeProvidedConfig(URI baseUri) {
        return ProjectConfigs.withAllDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of("com.example:runtime-tool", "1.0.0"),
                Set.of(),
                Map.of("jakarta.servlet:jakarta.servlet-api", "6.1.0"),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                null,
                null,
                null);
    }

    static ProjectConfig devConfig(URI baseUri) {
        return ProjectConfigs.withAllDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("test", baseUri.toString()),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of("com.example:devtools", "1.0.0"),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                null,
                null,
                null);
    }

    static ProjectConfig springBootPlatformConfig(URI baseUri) {
        return platformConfig(baseUri).withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT));
    }

    static ProjectConfig springBootWarPlatformConfig(URI baseUri) {
        return platformConfig(baseUri).withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT_WAR));
    }

    static ProjectConfig springBootNativePlatformConfig(URI baseUri) {
        return platformConfig(baseUri)
                .withFrameworkSettings(new FrameworkSettings(
                        new SpringBootSettings(true),
                        QuarkusSettings.defaults()));
    }
}
