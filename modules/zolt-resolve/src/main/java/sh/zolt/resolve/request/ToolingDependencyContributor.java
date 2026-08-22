package sh.zolt.resolve.request;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.dependency.VersionComparator;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.request.tooling.GeneratedSourceToolingDependencyContributor;
import sh.zolt.resolve.request.tooling.SpringBootToolingDependencyContributor;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ToolingDependencyContributor {
    private static final VersionComparator VERSION_COMPARATOR = new VersionComparator();
    private static final PackageId JUNIT_PLATFORM_CONSOLE_PACKAGE = new PackageId(
            "org.junit.platform",
            "junit-platform-console");
    private static final String JUNIT_PLATFORM_CONSOLE_VERSION = "1.11.4";
    private static final PackageId JACOCO_AGENT_PACKAGE = new PackageId(
            "org.jacoco",
            "org.jacoco.agent");
    private static final PackageId JACOCO_CLI_PACKAGE = new PackageId(
            "org.jacoco",
            "org.jacoco.cli");
    private static final String JACOCO_VERSION = "0.8.14";

    private final GeneratedSourceToolingDependencyContributor generatedSourceToolingDependencyContributor;
    private final SpringBootToolingDependencyContributor springBootToolingDependencyContributor;

    ToolingDependencyContributor(CoordinateParser coordinateParser) {
        this(
                coordinateParser,
                new GeneratedSourceToolingDependencyContributor(coordinateParser),
                new SpringBootToolingDependencyContributor());
    }

    ToolingDependencyContributor(
            CoordinateParser coordinateParser,
            GeneratedSourceToolingDependencyContributor generatedSourceToolingDependencyContributor) {
        this(coordinateParser, generatedSourceToolingDependencyContributor, new SpringBootToolingDependencyContributor());
    }

    ToolingDependencyContributor(
            CoordinateParser coordinateParser,
            GeneratedSourceToolingDependencyContributor generatedSourceToolingDependencyContributor,
            SpringBootToolingDependencyContributor springBootToolingDependencyContributor) {
        this.generatedSourceToolingDependencyContributor = generatedSourceToolingDependencyContributor == null
                ? new GeneratedSourceToolingDependencyContributor(coordinateParser)
                : generatedSourceToolingDependencyContributor;
        this.springBootToolingDependencyContributor = springBootToolingDependencyContributor == null
                ? new SpringBootToolingDependencyContributor()
                : springBootToolingDependencyContributor;
    }

    void contribute(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            List<DependencyRequest> requests,
            boolean includeCoverageTooling) {
        addTestToolRequests(config, projectManagedVersions, requests);
        springBootToolingDependencyContributor.contribute(config, projectManagedVersions, requests);
        generatedSourceToolingDependencyContributor.contribute(config, requests);
        if (includeCoverageTooling) {
            addCoverageToolRequests(config, requests);
        }
    }

    private void addTestToolRequests(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            List<DependencyRequest> requests) {
        if (!hasTestInputs(config)) {
            return;
        }
        boolean consoleAlreadyOnTestClasspath = requests.stream()
                .anyMatch(request -> request.packageId().groupId().equals("org.junit.platform")
                        && request.packageId().artifactId().startsWith("junit-platform-console")
                        && request.scope().entersTestRuntimeClasspath());
        if (consoleAlreadyOnTestClasspath) {
            return;
        }
        JunitConsoleVersion selection = junitConsoleVersion(projectManagedVersions, requests);
        requests.add(new DependencyRequest(
                JUNIT_PLATFORM_CONSOLE_PACKAGE,
                selection.version(),
                DependencyScope.TEST,
                RequestOrigin.TRANSITIVE,
                selection.origin()));
    }

    private static JunitConsoleVersion junitConsoleVersion(
            Map<PackageId, String> projectManagedVersions,
            List<DependencyRequest> requests) {
        Optional<String> declaredVersion = junitPlatformVersion(requests, RequestVersionOrigin.DECLARED);
        if (declaredVersion.isPresent()) {
            return JunitConsoleVersion.injected(declaredVersion.orElseThrow());
        }
        String managedVersion = projectManagedVersions.get(JUNIT_PLATFORM_CONSOLE_PACKAGE);
        if (managedVersion != null && !managedVersion.isBlank()) {
            return new JunitConsoleVersion(managedVersion, RequestVersionOrigin.MANAGED);
        }
        return JunitConsoleVersion.injected(
                junitPlatformVersion(requests, RequestVersionOrigin.MANAGED)
                        .orElse(JUNIT_PLATFORM_CONSOLE_VERSION));
    }

    private static Optional<String> junitPlatformVersion(
            List<DependencyRequest> requests,
            RequestVersionOrigin versionOrigin) {
        return requests.stream()
                .filter(request -> request.scope().entersTestRuntimeClasspath())
                .filter(request -> request.versionOrigin() == versionOrigin)
                .map(ToolingDependencyContributor::junitPlatformVersion)
                .flatMap(Optional::stream)
                .max(VERSION_COMPARATOR);
    }

    private static Optional<String> junitPlatformVersion(DependencyRequest request) {
        String group = request.packageId().groupId();
        String version = request.requestedVersion();
        if (group.equals("org.junit.platform")) {
            return Optional.of(version);
        }
        if ((group.equals("org.junit.jupiter") || group.equals("org.junit.vintage"))
                && version.startsWith("5.")) {
            return Optional.of("1." + version.substring(2));
        }
        if ((group.equals("org.junit.jupiter") || group.equals("org.junit.vintage"))
                && version.startsWith("6.")) {
            return Optional.of(version);
        }
        return Optional.empty();
    }

    private void addCoverageToolRequests(
            ProjectConfig config,
            List<DependencyRequest> requests) {
        if (!hasTestInputs(config)) {
            return;
        }
        boolean agentAlreadyRequested = requests.stream()
                .anyMatch(request -> request.packageId().equals(JACOCO_AGENT_PACKAGE)
                        && request.scope() == DependencyScope.TOOL_COVERAGE);
        if (!agentAlreadyRequested) {
            requests.add(new DependencyRequest(
                    JACOCO_AGENT_PACKAGE,
                    JACOCO_VERSION,
                    DependencyScope.TOOL_COVERAGE,
                    RequestOrigin.TRANSITIVE,
                    Optional.of(ArtifactDescriptor.jar(
                            new Coordinate(
                                    JACOCO_AGENT_PACKAGE.groupId(),
                                    JACOCO_AGENT_PACKAGE.artifactId(),
                                    Optional.of(JACOCO_VERSION)),
                            Optional.of("runtime"))),
                    List.of(),
                    false,
                    RequestVersionOrigin.INJECTED));
        }
        boolean cliAlreadyRequested = requests.stream()
                .anyMatch(request -> request.packageId().equals(JACOCO_CLI_PACKAGE)
                        && request.scope() == DependencyScope.TOOL_COVERAGE);
        if (!cliAlreadyRequested) {
            requests.add(new DependencyRequest(
                    JACOCO_CLI_PACKAGE,
                    JACOCO_VERSION,
                    DependencyScope.TOOL_COVERAGE,
                    RequestOrigin.TRANSITIVE,
                    RequestVersionOrigin.INJECTED));
        }
    }

    private static boolean hasTestInputs(ProjectConfig config) {
        return !config.testDependencies().isEmpty()
                || !config.managedTestDependencies().isEmpty()
                || !config.workspaceTestDependencies().isEmpty()
                || !config.testAnnotationProcessors().isEmpty()
                || !config.managedTestAnnotationProcessors().isEmpty()
                || !config.workspaceTestAnnotationProcessors().isEmpty();
    }

    private record JunitConsoleVersion(String version, RequestVersionOrigin origin) {
        private static JunitConsoleVersion injected(String version) {
            return new JunitConsoleVersion(version, RequestVersionOrigin.INJECTED);
        }
    }
}
