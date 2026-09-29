package sh.zolt.ide;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record IdeModel(
        int schemaVersion,
        ProjectInfo project,
        JavaInfo java,
        CompilerInfo compiler,
        TestRuntimeInfo testRuntime,
        PackageInfo packageInfo,
        PathInfo paths,
        List<SourceRoot> sourceRoots,
        List<GeneratedSourceInfo> generatedSources,
        List<ResourceRoot> resourceRoots,
        OutputInfo outputs,
        DependencyInfo dependencies,
        ClasspathInfo classpaths,
        FrameworkInfo frameworks,
        List<Diagnostic> diagnostics) {
    public IdeModel {
        sourceRoots = List.copyOf(sourceRoots);
        generatedSources = List.copyOf(generatedSources);
        resourceRoots = List.copyOf(resourceRoots);
        diagnostics = List.copyOf(diagnostics);
    }

    public record ProjectInfo(
            String name,
            String group,
            String version,
            String mainClass) {
    }

    public record JavaInfo(
            String version,
            String detectedVersion,
            String javaHome) {
    }

    public record CompilerInfo(
            String release,
            String effectiveRelease,
            String encoding,
            List<String> args,
            List<String> testArgs,
            Path generatedSources,
            Path generatedTestSources) {
        public CompilerInfo {
            args = List.copyOf(args);
            testArgs = List.copyOf(testArgs);
        }
    }

    public record TestRuntimeInfo(
            List<String> jvmArgs,
            Map<String, String> systemProperties,
            Map<String, String> environment,
            List<String> events) {
        public TestRuntimeInfo {
            jvmArgs = List.copyOf(jvmArgs);
            systemProperties = Map.copyOf(systemProperties);
            environment = Map.copyOf(environment);
            events = List.copyOf(events);
        }
    }

    public record PackageInfo(
            String mode,
            boolean sources,
            boolean javadoc,
            boolean tests,
            Path mainJar,
            Path sourcesJar,
            Path javadocJar,
            Path testsJar,
            PublicationInfo metadata,
            Map<String, String> manifestAttributes) {
        public PackageInfo {
            manifestAttributes = Map.copyOf(manifestAttributes);
        }
    }

    public record PublicationInfo(
            String name,
            String description,
            String url,
            String license,
            List<String> developers,
            String scm,
            String issues) {
        public PublicationInfo {
            developers = List.copyOf(developers);
        }
    }

    public record PathInfo(
            Path root,
            Path config,
            Path lockfile) {
    }

    public record SourceRoot(
            String id,
            String kind,
            String language,
            Path path,
            boolean generated) {
    }

    public record GeneratedSourceInfo(
            String id,
            String sourceRootId,
            String scope,
            String kind,
            String language,
            Path output,
            List<Path> inputs,
            boolean required,
            boolean clean,
            String ownership,
            String compileLane,
            String freshness,
            boolean outputExists,
            boolean inputsPresent,
            String toolArtifact,
            String toolVersionRef,
            String toolFingerprint,
            String optionsFingerprint) {
        public GeneratedSourceInfo {
            inputs = List.copyOf(inputs);
        }

        public GeneratedSourceInfo(
                String id,
                String sourceRootId,
                String scope,
                String kind,
                String language,
                Path output,
                List<Path> inputs,
                boolean required,
                boolean clean,
                String ownership,
                String compileLane,
                String freshness,
                boolean outputExists,
                boolean inputsPresent,
                String toolArtifact,
                String toolFingerprint,
                String optionsFingerprint) {
            this(
                    id,
                    sourceRootId,
                    scope,
                    kind,
                    language,
                    output,
                    inputs,
                    required,
                    clean,
                    ownership,
                    compileLane,
                    freshness,
                    outputExists,
                    inputsPresent,
                    toolArtifact,
                    null,
                    toolFingerprint,
                    optionsFingerprint);
        }
    }

    public record ResourceRoot(
            String id,
            String kind,
            Path path) {
    }

    public record OutputInfo(
            Path mainClasses,
            Path testClasses,
            Path integrationTestClasses,
            Path packagePath) {
        public OutputInfo(
                Path mainClasses,
                Path testClasses,
                Path packagePath) {
            this(mainClasses, testClasses, null, packagePath);
        }
    }

    public record DependencyInfo(
            Map<String, String> versionAliases,
            List<DependencyDeclaration> api,
            List<DependencyDeclaration> implementation,
            List<DependencyDeclaration> runtime,
            List<DependencyDeclaration> provided,
            List<DependencyDeclaration> dev,
            List<DependencyDeclaration> test,
            List<DependencyDeclaration> annotationProcessors,
            List<DependencyDeclaration> testAnnotationProcessors) {
        public DependencyInfo {
            versionAliases = versionAliases == null ? Map.of() : Map.copyOf(versionAliases);
            api = List.copyOf(api);
            implementation = List.copyOf(implementation);
            runtime = List.copyOf(runtime);
            provided = List.copyOf(provided);
            dev = List.copyOf(dev);
            test = List.copyOf(test);
            annotationProcessors = List.copyOf(annotationProcessors);
            testAnnotationProcessors = List.copyOf(testAnnotationProcessors);
        }

        public DependencyInfo(
                List<DependencyDeclaration> api,
                List<DependencyDeclaration> implementation,
                List<DependencyDeclaration> runtime,
                List<DependencyDeclaration> provided,
                List<DependencyDeclaration> dev,
                List<DependencyDeclaration> test,
                List<DependencyDeclaration> annotationProcessors,
                List<DependencyDeclaration> testAnnotationProcessors) {
            this(
                    Map.of(),
                    api,
                    implementation,
                    runtime,
                    provided,
                    dev,
                    test,
                    annotationProcessors,
                    testAnnotationProcessors);
        }
    }

    public record DependencyDeclaration(
            String coordinate,
            String version,
            String versionRef,
            boolean managed,
            String workspace,
            boolean optional,
            boolean publishOnly,
            List<String> exclusions) {
        public DependencyDeclaration {
            exclusions = List.copyOf(exclusions);
        }

        public DependencyDeclaration(
                String coordinate,
                String version,
                boolean managed,
                String workspace) {
            this(coordinate, version, null, managed, workspace, false, false, List.of());
        }

        public DependencyDeclaration(
                String coordinate,
                String version,
                boolean managed,
                String workspace,
                boolean optional,
                boolean publishOnly,
                List<String> exclusions) {
            this(coordinate, version, null, managed, workspace, optional, publishOnly, exclusions);
        }
    }

    public record ClasspathInfo(
            List<Path> compile,
            List<Path> runtime,
            List<Path> test,
            List<Path> processor,
            List<Path> testProcessor,
            List<Path> quarkusDeployment) {
        public ClasspathInfo {
            compile = List.copyOf(compile);
            runtime = List.copyOf(runtime);
            test = List.copyOf(test);
            processor = List.copyOf(processor);
            testProcessor = List.copyOf(testProcessor);
            quarkusDeployment = List.copyOf(quarkusDeployment);
        }
    }

    public record FrameworkInfo(
            QuarkusInfo quarkus) {
    }

    public record QuarkusInfo(
            boolean enabled,
            String packageMode,
            String augmentationStatus,
            String inputFingerprint,
            String recordedInputFingerprint,
            Path augmentationMetadata,
            Path augmentationDirectory,
            Path packageDirectory,
            Path runnerJar,
            Path generatedBytecodeJar,
            Path transformedBytecodeJar,
            List<QuarkusGeneratedOutput> generatedOutputs,
            List<Path> deploymentClasspath) {
        public QuarkusInfo(
                boolean enabled,
                String packageMode,
                String augmentationStatus,
                String inputFingerprint,
                String recordedInputFingerprint,
                Path augmentationMetadata,
                Path augmentationDirectory,
                Path packageDirectory,
                Path runnerJar,
                Path generatedBytecodeJar,
                Path transformedBytecodeJar,
                List<Path> deploymentClasspath) {
            this(
                    enabled,
                    packageMode,
                    augmentationStatus,
                    inputFingerprint,
                    recordedInputFingerprint,
                    augmentationMetadata,
                    augmentationDirectory,
                    packageDirectory,
                    runnerJar,
                    generatedBytecodeJar,
                    transformedBytecodeJar,
                    List.of(),
                    deploymentClasspath);
        }

        public QuarkusInfo {
            generatedOutputs = List.copyOf(generatedOutputs);
            deploymentClasspath = List.copyOf(deploymentClasspath);
        }
    }

    public record QuarkusGeneratedOutput(
            String id,
            String kind,
            Path path,
            boolean exists) {
    }

    public record Diagnostic(
            String severity,
            String code,
            String message,
            Path path,
            String nextStep) {
    }
}
