package sh.zolt.project;

import java.util.Map;
import java.util.Set;

public record ProjectConfig(
        ProjectMetadata project,
        Map<String, String> repositories,
        Map<String, RepositorySettings> repositorySettings,
        Map<String, RepositoryCredentialSettings> repositoryCredentials,
        Map<String, String> versionAliases,
        Map<String, String> platforms,
        Map<String, String> apiDependencies,
        Set<String> managedApiDependencies,
        Map<String, String> workspaceApiDependencies,
        Map<String, String> dependencies,
        Set<String> managedDependencies,
        Map<String, String> workspaceDependencies,
        Map<String, String> runtimeDependencies,
        Set<String> managedRuntimeDependencies,
        Map<String, String> providedDependencies,
        Set<String> managedProvidedDependencies,
        Map<String, String> devDependencies,
        Set<String> managedDevDependencies,
        Map<String, String> testDependencies,
        Set<String> managedTestDependencies,
        Map<String, String> workspaceTestDependencies,
        Map<String, String> annotationProcessors,
        Set<String> managedAnnotationProcessors,
        Map<String, String> workspaceAnnotationProcessors,
        Map<String, String> testAnnotationProcessors,
        Set<String> managedTestAnnotationProcessors,
        Map<String, String> workspaceTestAnnotationProcessors,
        DependencyPolicySettings dependencyPolicy,
        BuildSettings build,
        NativeSettings nativeSettings,
        CompilerSettings compilerSettings,
        PackageSettings packageSettings,
        FrameworkSettings frameworkSettings,
        Map<String, DependencyMetadata> dependencyMetadata) implements RepositoryConfiguration {
    public static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";

    public ProjectConfig {
        repositorySettings = repositorySettings == null || repositorySettings.isEmpty()
                ? ProjectConfigNormalizer.repositorySettingsFromUrls(repositories)
                : ProjectConfigNormalizer.orderedRepositorySettings(repositorySettings);
        repositories = repositories == null || repositories.isEmpty()
                ? ProjectConfigNormalizer.repositoryUrls(repositorySettings)
                : ProjectConfigNormalizer.orderedMap(repositories);
        repositoryCredentials = ProjectConfigNormalizer.orderedRepositoryCredentials(repositoryCredentials);
        versionAliases = ProjectConfigNormalizer.orderedMap(versionAliases);
        platforms = ProjectConfigNormalizer.orderedMap(platforms);
        apiDependencies = ProjectConfigNormalizer.orderedMap(apiDependencies);
        managedApiDependencies = ProjectConfigNormalizer.orderedSet(managedApiDependencies);
        workspaceApiDependencies = ProjectConfigNormalizer.orderedMap(workspaceApiDependencies);
        dependencies = ProjectConfigNormalizer.orderedMap(dependencies);
        managedDependencies = ProjectConfigNormalizer.orderedSet(managedDependencies);
        workspaceDependencies = ProjectConfigNormalizer.orderedMap(workspaceDependencies);
        runtimeDependencies = ProjectConfigNormalizer.orderedMap(runtimeDependencies);
        managedRuntimeDependencies = ProjectConfigNormalizer.orderedSet(managedRuntimeDependencies);
        providedDependencies = ProjectConfigNormalizer.orderedMap(providedDependencies);
        managedProvidedDependencies = ProjectConfigNormalizer.orderedSet(managedProvidedDependencies);
        devDependencies = ProjectConfigNormalizer.orderedMap(devDependencies);
        managedDevDependencies = ProjectConfigNormalizer.orderedSet(managedDevDependencies);
        testDependencies = ProjectConfigNormalizer.orderedMap(testDependencies);
        managedTestDependencies = ProjectConfigNormalizer.orderedSet(managedTestDependencies);
        workspaceTestDependencies = ProjectConfigNormalizer.orderedMap(workspaceTestDependencies);
        annotationProcessors = ProjectConfigNormalizer.orderedMap(annotationProcessors);
        managedAnnotationProcessors = ProjectConfigNormalizer.orderedSet(managedAnnotationProcessors);
        workspaceAnnotationProcessors = ProjectConfigNormalizer.orderedMap(workspaceAnnotationProcessors);
        testAnnotationProcessors = ProjectConfigNormalizer.orderedMap(testAnnotationProcessors);
        managedTestAnnotationProcessors = ProjectConfigNormalizer.orderedSet(managedTestAnnotationProcessors);
        workspaceTestAnnotationProcessors = ProjectConfigNormalizer.orderedMap(workspaceTestAnnotationProcessors);
        dependencyPolicy = dependencyPolicy == null ? DependencyPolicySettings.defaults() : dependencyPolicy;
        nativeSettings = nativeSettings == null ? NativeSettings.defaults() : nativeSettings;
        compilerSettings = compilerSettings == null ? CompilerSettings.defaults() : compilerSettings;
        packageSettings = packageSettings == null ? PackageSettings.defaults() : packageSettings;
        frameworkSettings = frameworkSettings == null ? FrameworkSettings.defaults() : frameworkSettings;
        dependencyMetadata = ProjectConfigNormalizer.orderedMetadataMap(dependencyMetadata);
    }

    ProjectConfig(ProjectConfigArguments arguments) {
        this(
                arguments.project(),
                arguments.repositories(),
                arguments.repositorySettings(),
                arguments.repositoryCredentials(),
                arguments.versionAliases(),
                arguments.platforms(),
                arguments.apiDependencies(),
                arguments.managedApiDependencies(),
                arguments.workspaceApiDependencies(),
                arguments.dependencies(),
                arguments.managedDependencies(),
                arguments.workspaceDependencies(),
                arguments.runtimeDependencies(),
                arguments.managedRuntimeDependencies(),
                arguments.providedDependencies(),
                arguments.managedProvidedDependencies(),
                arguments.devDependencies(),
                arguments.managedDevDependencies(),
                arguments.testDependencies(),
                arguments.managedTestDependencies(),
                arguments.workspaceTestDependencies(),
                arguments.annotationProcessors(),
                arguments.managedAnnotationProcessors(),
                arguments.workspaceAnnotationProcessors(),
                arguments.testAnnotationProcessors(),
                arguments.managedTestAnnotationProcessors(),
                arguments.workspaceTestAnnotationProcessors(),
                arguments.dependencyPolicy(),
                arguments.build(),
                arguments.nativeSettings(),
                arguments.compilerSettings(),
                arguments.packageSettings(),
                arguments.frameworkSettings(),
                arguments.dependencyMetadata());
    }

    public ProjectConfig(
            ProjectMetadata project,
            Map<String, String> repositories,
            Map<String, RepositorySettings> repositorySettings,
            Map<String, RepositoryCredentialSettings> repositoryCredentials,
            Map<String, String> platforms,
            Map<String, String> apiDependencies,
            Set<String> managedApiDependencies,
            Map<String, String> workspaceApiDependencies,
            Map<String, String> dependencies,
            Set<String> managedDependencies,
            Map<String, String> workspaceDependencies,
            Map<String, String> runtimeDependencies,
            Set<String> managedRuntimeDependencies,
            Map<String, String> providedDependencies,
            Set<String> managedProvidedDependencies,
            Map<String, String> devDependencies,
            Set<String> managedDevDependencies,
            Map<String, String> testDependencies,
            Set<String> managedTestDependencies,
            Map<String, String> workspaceTestDependencies,
            Map<String, String> annotationProcessors,
            Set<String> managedAnnotationProcessors,
            Map<String, String> workspaceAnnotationProcessors,
            Map<String, String> testAnnotationProcessors,
            Set<String> managedTestAnnotationProcessors,
            Map<String, String> workspaceTestAnnotationProcessors,
            BuildSettings build,
            NativeSettings nativeSettings,
            CompilerSettings compilerSettings,
            PackageSettings packageSettings,
            FrameworkSettings frameworkSettings,
            Map<String, DependencyMetadata> dependencyMetadata) {
        this(ProjectConfigConstruction.repositorySettings(
                project,
                repositories,
                repositorySettings,
                repositoryCredentials,
                platforms,
                apiDependencies,
                managedApiDependencies,
                workspaceApiDependencies,
                dependencies,
                managedDependencies,
                workspaceDependencies,
                runtimeDependencies,
                managedRuntimeDependencies,
                providedDependencies,
                managedProvidedDependencies,
                devDependencies,
                managedDevDependencies,
                testDependencies,
                managedTestDependencies,
                workspaceTestDependencies,
                annotationProcessors,
                managedAnnotationProcessors,
                workspaceAnnotationProcessors,
                testAnnotationProcessors,
                managedTestAnnotationProcessors,
                workspaceTestAnnotationProcessors,
                build,
                nativeSettings,
                compilerSettings,
                packageSettings,
                frameworkSettings,
                dependencyMetadata));
    }

    public ProjectConfig(
            ProjectMetadata project,
            Map<String, String> repositories,
            Map<String, String> platforms,
            Map<String, String> apiDependencies,
            Set<String> managedApiDependencies,
            Map<String, String> workspaceApiDependencies,
            Map<String, String> dependencies,
            Set<String> managedDependencies,
            Map<String, String> workspaceDependencies,
            Map<String, String> runtimeDependencies,
            Set<String> managedRuntimeDependencies,
            Map<String, String> providedDependencies,
            Set<String> managedProvidedDependencies,
            Map<String, String> devDependencies,
            Set<String> managedDevDependencies,
            Map<String, String> testDependencies,
            Set<String> managedTestDependencies,
            Map<String, String> workspaceTestDependencies,
            Map<String, String> annotationProcessors,
            Set<String> managedAnnotationProcessors,
            Map<String, String> workspaceAnnotationProcessors,
            Map<String, String> testAnnotationProcessors,
            Set<String> managedTestAnnotationProcessors,
            Map<String, String> workspaceTestAnnotationProcessors,
            BuildSettings build,
            NativeSettings nativeSettings,
            CompilerSettings compilerSettings) {
        this(ProjectConfigConstruction.full(
                project,
                repositories,
                platforms,
                apiDependencies,
                managedApiDependencies,
                workspaceApiDependencies,
                dependencies,
                managedDependencies,
                workspaceDependencies,
                runtimeDependencies,
                managedRuntimeDependencies,
                providedDependencies,
                managedProvidedDependencies,
                devDependencies,
                managedDevDependencies,
                testDependencies,
                managedTestDependencies,
                workspaceTestDependencies,
                annotationProcessors,
                managedAnnotationProcessors,
                workspaceAnnotationProcessors,
                testAnnotationProcessors,
                managedTestAnnotationProcessors,
                workspaceTestAnnotationProcessors,
                build,
                nativeSettings,
                compilerSettings));
    }

    public ProjectConfig(
            ProjectMetadata project,
            Map<String, String> repositories,
            Map<String, String> platforms,
            Map<String, String> apiDependencies,
            Set<String> managedApiDependencies,
            Map<String, String> workspaceApiDependencies,
            Map<String, String> dependencies,
            Set<String> managedDependencies,
            Map<String, String> workspaceDependencies,
            Map<String, String> runtimeDependencies,
            Set<String> managedRuntimeDependencies,
            Map<String, String> providedDependencies,
            Set<String> managedProvidedDependencies,
            Map<String, String> testDependencies,
            Set<String> managedTestDependencies,
            Map<String, String> workspaceTestDependencies,
            Map<String, String> annotationProcessors,
            Set<String> managedAnnotationProcessors,
            Map<String, String> workspaceAnnotationProcessors,
            Map<String, String> testAnnotationProcessors,
            Set<String> managedTestAnnotationProcessors,
            Map<String, String> workspaceTestAnnotationProcessors,
            BuildSettings build,
            NativeSettings nativeSettings,
            CompilerSettings compilerSettings) {
        this(ProjectConfigConstruction.withoutDev(
                project,
                repositories,
                platforms,
                apiDependencies,
                managedApiDependencies,
                workspaceApiDependencies,
                dependencies,
                managedDependencies,
                workspaceDependencies,
                runtimeDependencies,
                managedRuntimeDependencies,
                providedDependencies,
                managedProvidedDependencies,
                testDependencies,
                managedTestDependencies,
                workspaceTestDependencies,
                annotationProcessors,
                managedAnnotationProcessors,
                workspaceAnnotationProcessors,
                testAnnotationProcessors,
                managedTestAnnotationProcessors,
                workspaceTestAnnotationProcessors,
                build,
                nativeSettings,
                compilerSettings));
    }

    public static Map<String, String> defaultRepositories() {
        return ProjectConfigNormalizer.defaultRepositories();
    }

    public static Map<String, RepositorySettings> defaultRepositorySettings() {
        return ProjectConfigNormalizer.defaultRepositorySettings();
    }

    public ProjectConfig withBuildSettings(BuildSettings build) {
        return ProjectConfigUpdater.withBuildSettings(this, build);
    }

    public ProjectConfig withVersion(String version) {
        return ProjectConfigUpdater.withVersion(this, version);
    }

    public ProjectConfig withVersionAliases(Map<String, String> versionAliases) {
        return ProjectConfigUpdater.withVersionAliases(this, versionAliases);
    }

    public ProjectConfig withPackageSettings(PackageSettings packageSettings) {
        return ProjectConfigUpdater.withPackageSettings(this, packageSettings);
    }

    public ProjectConfig withFrameworkSettings(FrameworkSettings frameworkSettings) {
        return ProjectConfigUpdater.withFrameworkSettings(this, frameworkSettings);
    }

    public ProjectConfig withDependencyPolicy(DependencyPolicySettings dependencyPolicy) {
        return ProjectConfigUpdater.withDependencyPolicy(this, dependencyPolicy);
    }

    public ProjectConfig withDependencyMetadata(Map<String, DependencyMetadata> dependencyMetadata) {
        return ProjectConfigUpdater.withDependencyMetadata(this, dependencyMetadata);
    }
}
