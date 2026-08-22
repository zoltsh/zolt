package sh.zolt.build.nativeimage;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Authoritative project, build, cache, and tool paths a native build must not own. */
final class NativeProtectedPaths {
    private NativeProtectedPaths() {
    }

    static List<ProtectedPath> collect(
            Path root,
            ProjectConfig config,
            Path cacheRoot,
            Path nativeImageExecutable,
            List<Path> packageOutputs) {
        List<ProtectedPath> paths = new ArrayList<>();
        addFixedProjectFiles(paths, root);
        addBuildInputs(paths, root, config.build());
        addBuildOutputs(paths, root, config);
        addFrameworkAndPublicationOutputs(paths, root, config);
        packageOutputs.forEach(path -> paths.add(new ProtectedPath("configured package output", path)));
        if (cacheRoot != null) {
            paths.add(new ProtectedPath("artifact cache root", cacheRoot.toAbsolutePath().normalize()));
        }
        executablePath(root, nativeImageExecutable).ifPresent(path ->
                paths.add(new ProtectedPath("configured native-image executable", path)));
        return List.copyOf(paths);
    }

    private static void addFixedProjectFiles(List<ProtectedPath> paths, Path root) {
        paths.add(new ProtectedPath("project manifest", root.resolve("zolt.toml")));
        paths.add(new ProtectedPath("project lockfile", ProjectLockfile.in(root)));
    }

    private static void addBuildInputs(List<ProtectedPath> paths, Path root, BuildSettings build) {
        addInputs(paths, root, "main source root", "[build].sources", build.sourceRoots());
        addInputs(paths, root, "test source root", "[test.sources].java", build.testSources());
        addInputs(paths, root, "Groovy test source root", "[test.sources].groovy", build.groovyTestSources());
        addInputs(paths, root, "integration-test source root", "[test.integration].sources", build.integrationTestSources());
        addInputs(paths, root, "main resource root", "[resources].main", build.resourceRoots());
        addInputs(paths, root, "test resource root", "[resources].test", build.testResourceRoots());
        addInputs(paths, root, "integration-test resource root", "[test.integration].resources", build.integrationTestResourceRoots());
        addGeneratedInputs(paths, root, "main", build.generatedMainSources());
        addGeneratedInputs(paths, root, "test", build.generatedTestSources());
    }

    private static void addFrameworkAndPublicationOutputs(
            List<ProtectedPath> paths,
            Path root,
            ProjectConfig config) {
        String outputRoot = config.build().outputRoot();
        addOutput(paths, root, "Spring Boot AOT output", "Spring Boot AOT output", outputRoot + "/spring-aot");
        addOutput(paths, root, "Quarkus build output", "Quarkus build output", outputRoot + "/quarkus");
        addOutput(paths, root, "Quarkus application output", "Quarkus application output", outputRoot + "/quarkus-app");
        addOutput(paths, root, "package assembly output", "package assembly output", outputRoot + "/zolt-package");
        addOutput(paths, root, "publication output", "publication output", outputRoot + "/publish");
    }

    private static void addBuildOutputs(List<ProtectedPath> paths, Path root, ProjectConfig config) {
        BuildSettings build = config.build();
        addOutput(paths, root, "compiled main classes", "[build.output].main", build.output());
        addOutput(paths, root, "compiled test classes", "[build.output].test", build.testOutput());
        addOutput(paths, root, "compiled integration-test classes", "[build.output].integration", build.integrationTestOutput());
        addOutput(paths, root, "generated main sources", "[compiler.generated].main", config.compilerSettings().generatedSources());
        addOutput(paths, root, "generated test sources", "[compiler.generated].test", config.compilerSettings().generatedTestSources());
        addGeneratedOutputs(paths, root, "main", build.generatedMainSources());
        addGeneratedOutputs(paths, root, "test", build.generatedTestSources());
    }

    private static void addInputs(
            List<ProtectedPath> paths,
            Path root,
            String kind,
            String key,
            List<String> configured) {
        for (int index = 0; index < configured.size(); index++) {
            paths.add(new ProtectedPath(kind, ProjectPaths.input(root, key + "[" + index + "]", configured.get(index))));
        }
    }

    private static void addGeneratedOutputs(
            List<ProtectedPath> paths,
            Path root,
            String scope,
            List<GeneratedSourceStep> steps) {
        for (GeneratedSourceStep step : steps) {
            addOutput(
                    paths,
                    root,
                    "generated " + scope + " output",
                    "[generated." + scope + "." + step.id() + "].output",
                    step.output());
        }
    }

    private static void addGeneratedInputs(
            List<ProtectedPath> paths,
            Path root,
            String scope,
            List<GeneratedSourceStep> steps) {
        for (GeneratedSourceStep step : steps) {
            addInputs(
                    paths,
                    root,
                    "generated " + scope + " input",
                    "[generated." + scope + "." + step.id() + "].inputs",
                    step.inputs());
        }
    }

    private static void addOutput(
            List<ProtectedPath> paths,
            Path root,
            String kind,
            String key,
            String configured) {
        paths.add(new ProtectedPath(kind, ProjectPaths.output(root, key, configured)));
    }

    private static java.util.Optional<Path> executablePath(Path root, Path executable) {
        if (executable == null || !filesystemPath(executable)) {
            return java.util.Optional.empty();
        }
        Path resolved = executable.isAbsolute() ? executable : root.resolve(executable);
        return java.util.Optional.of(resolved.toAbsolutePath().normalize());
    }

    private static boolean filesystemPath(Path path) {
        String value = path.toString();
        return path.isAbsolute() || path.getNameCount() > 1 || value.contains("/") || value.contains("\\");
    }

    record ProtectedPath(String kind, Path path) {
    }
}
