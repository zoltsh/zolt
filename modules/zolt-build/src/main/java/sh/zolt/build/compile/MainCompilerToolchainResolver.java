package sh.zolt.build.compile;

import java.util.List;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.project.ProjectConfig;

/** Selects the one compiler family responsible for the main source set. */
public final class MainCompilerToolchainResolver {
    private final GroovyCompilerToolchainResolver groovyResolver;
    private final KotlinCompilerToolchainResolver kotlinResolver;

    public MainCompilerToolchainResolver() {
        this(new GroovyCompilerToolchainResolver(), new KotlinCompilerToolchainResolver());
    }

    MainCompilerToolchainResolver(
            GroovyCompilerToolchainResolver groovyResolver,
            KotlinCompilerToolchainResolver kotlinResolver) {
        this.groovyResolver = Objects.requireNonNull(groovyResolver);
        this.kotlinResolver = Objects.requireNonNull(kotlinResolver);
    }

    public MainCompilerToolchain resolve(
            SourceDiscoveryResult sources,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            boolean verifiedPackageMetadataAvailable) {
        Objects.requireNonNull(sources, "Main source discovery result is required.");
        Objects.requireNonNull(config, "Project configuration is required.");
        boolean groovy = !sources.groovyMainSources().isEmpty();
        boolean kotlin = !sources.kotlinMainSources().isEmpty();
        if (groovy && kotlin) {
            throw BuildException.actionable(
                    "The main source set combines Groovy and Kotlin, which Zolt does not support.",
                    "Keep one non-Java main language by removing either the Groovy or Kotlin sources, "
                            + "then run `zolt build` again.");
        }
        if (!groovy && !kotlin) {
            return MainCompilerToolchain.javaOnly();
        }
        if (!verifiedPackageMetadataAvailable) {
            throw missingMetadata(kotlin ? "Kotlin" : "Groovy", kotlin
                    ? KotlinCompilerToolchain.COORDINATE
                    : GroovyCompilerToolchain.COORDINATE);
        }
        if (groovy) {
            return MainCompilerToolchain.groovy(groovyResolver.resolve(
                    packages,
                    GroovyCompilerToolchainResolver.SourceSet.MAIN,
                    config.compilerSettings().groovyVersion()));
        }
        return MainCompilerToolchain.kotlin(kotlinResolver.resolve(
                packages,
                config.compilerSettings().kotlinVersion(),
                KotlinCompilationScope.MAIN,
                config.compilerSettings().kotlinPlugins()));
    }

    private static BuildException missingMetadata(String language, String coordinate) {
        return BuildException.actionable(
                language + " main compilation requires verified resolved package metadata, which the legacy "
                        + "BuildService.build(project, config, ClasspathSet) API does not provide.",
                "Use a cache-root build overload so Zolt can resolve and verify " + coordinate
                        + ", or use the metadata-aware workspace build API.");
    }
}
