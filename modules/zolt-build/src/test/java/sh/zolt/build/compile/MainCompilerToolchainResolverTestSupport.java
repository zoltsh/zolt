package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

abstract class MainCompilerToolchainResolverTestSupport
        extends KotlinCompilerToolchainResolverTestSupport {
    static final PackageId GROOVY = new PackageId("org.apache.groovy", "groovy");
    static final String GROOVY_VERSION = "4.0.22";
    static final String GROOVY_COMPILER_ENTRY =
            "org/codehaus/groovy/tools/FileSystemCompiler.class";

    final MainCompilerToolchainResolver mainResolver = new MainCompilerToolchainResolver();

    SourceDiscoveryResult javaSources() {
        return sources(List.of(), List.of());
    }

    SourceDiscoveryResult groovySources() {
        return sources(List.of(Path.of("src/main/groovy/Main.groovy")), List.of());
    }

    SourceDiscoveryResult kotlinSources() {
        return sources(List.of(), List.of(Path.of("src/main/kotlin/Main.kt")));
    }

    SourceDiscoveryResult mixedSources() {
        return sources(
                List.of(Path.of("src/main/groovy/Main.groovy")),
                List.of(Path.of("src/main/kotlin/Main.kt")));
    }

    ProjectConfig config(String groovyVersion, String kotlinVersion) {
        CompilerSettings compiler = new CompilerSettings(
                "target/generated/sources/annotations",
                "target/generated/test-sources/annotations",
                "",
                "",
                List.of(),
                List.of(),
                CompilerSettings.PLATFORM_API_RELEASE,
                "",
                groovyVersion,
                kotlinVersion);
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        "21",
                        Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                NativeSettings.defaults(),
                compiler);
    }

    VerifiedJar groovyJar() throws IOException {
        Path jar = tempDir.resolve("artifacts/groovy-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(
                Attributes.Name.IMPLEMENTATION_VERSION,
                GROOVY_VERSION);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            writeEntry(output, GROOVY_COMPILER_ENTRY, new byte[] {0});
        }
        return verifiedArtifact(jar, GROOVY, GROOVY_VERSION);
    }

    List<ResolvedClasspathPackage> legacyGroovyPackages(VerifiedJar groovy) {
        return List.of(dependency(
                GROOVY,
                groovy,
                GROOVY_VERSION,
                DependencyScope.COMPILE,
                true,
                NestedArtifactIdentity.external(GROOVY, GROOVY_VERSION)));
    }

    List<ResolvedClasspathPackage> configuredGroovyPackages(VerifiedJar groovy) {
        return List.of(
                dependency(
                        GROOVY,
                        groovy,
                        GROOVY_VERSION,
                        DependencyScope.TOOL_GROOVY,
                        true,
                        NestedArtifactIdentity.external(GROOVY, GROOVY_VERSION)),
                dependency(
                        GROOVY,
                        groovy,
                        GROOVY_VERSION,
                        DependencyScope.COMPILE,
                        true,
                        NestedArtifactIdentity.external(GROOVY, GROOVY_VERSION)));
    }

    private static SourceDiscoveryResult sources(
            List<Path> groovy,
            List<Path> kotlin) {
        return new SourceDiscoveryResult(
                List.of(Path.of("src/main/java/Main.java")),
                groovy,
                kotlin,
                List.of(),
                List.of(),
                List.of());
    }
}
