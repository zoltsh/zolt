package sh.zolt.build;

import sh.zolt.build.generatedsource.ExecGeneratedSourceService;
import sh.zolt.build.metadata.BuildMetadataGenerator;
import sh.zolt.build.metadata.BuildMetadataResult;
import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.build.resources.ResourceCopyResult;
import sh.zolt.build.springboot.SpringBootAotGenerationService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class BuildOutputFinalizer {
    private final JdkChecker jdkChecker;
    private final ResourceCopier resourceCopier;
    private final BuildMetadataGenerator metadataGenerator;
    private final ExecGeneratedSourceService execGeneratedSourceService;
    private final SpringBootAotGenerationService springBootAotGenerationService;

    BuildOutputFinalizer(BuildServiceDependencies dependencies) {
        this.jdkChecker = dependencies.jdkDetector();
        this.resourceCopier = dependencies.resourceCopier();
        this.metadataGenerator = dependencies.buildMetadataGenerator();
        this.execGeneratedSourceService = dependencies.execGeneratedSourceService();
        this.springBootAotGenerationService = dependencies.springBootAotGenerationService();
    }

    Result afterCompile(
            Path projectDirectory,
            ProjectConfig config,
            Path outputDirectory,
            JdkStatus jdkStatus,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> classpathPackages,
            boolean offline) {
        execGeneratedSourceService.generateMainPostCompile(
                projectDirectory,
                config,
                classpathPackages,
                offline);
        Result result = currentResourcesAndMetadata(
                projectDirectory,
                config,
                outputDirectory);
        springBootAotGenerationService.generate(
                projectDirectory,
                config,
                jdkStatus,
                springBootAotRuntimeClasspath(config, classpaths.runtime(), classpathPackages),
                springBootAotClasspath(config, classpathPackages));
        return result;
    }

    Result ensureCleanMemberCurrent(
            Path projectDirectory,
            ProjectConfig config,
            ClasspathSet classpaths) {
        if (config.packageSettings().mode() == PackageMode.BOM) {
            return Result.empty();
        }
        if (!classpaths.processor().entries().isEmpty()
                || !config.build().generatedMainSources().isEmpty()
                || config.frameworkSettings().springBoot().nativeEnabled()) {
            throw new BuildException(
                    "Workspace clean-member finalization cannot bypass generated, processor, or Spring AOT build semantics.");
        }
        JdkStatus jdkStatus = jdkChecker.detect(config.project().java());
        if (!jdkStatus.ok()) {
            throw BuildException.actionable(
                    "JDK check failed.",
                    String.join(" ", jdkStatus.problems()));
        }
        Path outputDirectory = projectDirectory.resolve(config.build().output());
        return currentResourcesAndMetadata(
                projectDirectory,
                config,
                outputDirectory);
    }

    private Result currentResourcesAndMetadata(
            Path projectDirectory,
            ProjectConfig config,
            Path outputDirectory) {
        ResourceCopyResult resources =
                resourceCopier.copyMainResources(projectDirectory, config);
        BuildMetadataResult metadata =
                metadataGenerator.generate(projectDirectory, config, outputDirectory);
        return new Result(resources.resourceCount() + metadata.generatedCount());
    }

    private static Classpath springBootAotClasspath(
            ProjectConfig config,
            List<ResolvedClasspathPackage> classpathPackages) {
        if (!config.frameworkSettings().springBoot().nativeEnabled()) {
            return new Classpath(List.of());
        }
        return new Classpath(classpathPackages.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_SPRING_AOT)
                .map(dependency -> dependency.resolvedPackage().jarPath())
                .toList());
    }

    private static Classpath springBootAotRuntimeClasspath(
            ProjectConfig config,
            Classpath runtimeClasspath,
            List<ResolvedClasspathPackage> classpathPackages) {
        PackageMode mode = config.packageSettings().mode();
        if (!config.frameworkSettings().springBoot().nativeEnabled()
                || (mode != PackageMode.SPRING_BOOT && mode != PackageMode.SPRING_BOOT_WAR)) {
            return runtimeClasspath;
        }
        Set<Path> implicitLoaders = classpathPackages.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.RUNTIME)
                .filter(dependency -> !dependency.resolvedPackage().direct())
                .filter(dependency -> {
                    var resolved = dependency.resolvedPackage();
                    var identity = resolved.artifactIdentity();
                    return SpringBootLoaderArtifact.isDefaultLoader(
                            resolved.packageId(),
                            identity.extension(),
                            identity.classifier());
                })
                .map(dependency -> dependency.resolvedPackage().jarPath())
                .collect(Collectors.toUnmodifiableSet());
        return new Classpath(runtimeClasspath.entries().stream()
                .filter(path -> !implicitLoaders.contains(path))
                .toList());
    }

    record Result(int generatedOutputCount) {
        static Result empty() {
            return new Result(0);
        }
    }
}
