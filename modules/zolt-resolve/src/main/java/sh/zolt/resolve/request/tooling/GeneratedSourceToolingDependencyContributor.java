package sh.zolt.resolve.request.tooling;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProtobufGenerationSettings;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public final class GeneratedSourceToolingDependencyContributor {
    private final CoordinateParser coordinateParser;

    public GeneratedSourceToolingDependencyContributor(CoordinateParser coordinateParser) {
        this.coordinateParser = coordinateParser;
    }

    public void contribute(ProjectConfig config, List<DependencyRequest> requests) {
        addOpenApiToolRequests(config, requests);
        addProtobufToolRequests(config, requests);
        addExecToolRequests(config, requests);
    }

    /**
     * Per-tool direct requests for every jvm-runner exec tool, grouped by tool name. Each named tool is
     * its own isolated resolution unit so versions mediate <em>within</em> a tool and never across tools
     * (two tools may pin incompatible versions of the same GA). The returned map is sorted by tool name
     * for deterministic lock assembly; process/project runners contribute nothing (no locked closure).
     */
    public Map<String, List<DependencyRequest>> execToolRequestGroups(ProjectConfig config) {
        Map<String, List<DependencyRequest>> groups = new TreeMap<>();
        for (GeneratedSourceStep step : execSteps(config)) {
            if (step.exec().tool().coordinates().isEmpty()) {
                continue;
            }
            String toolName = step.exec().toolName();
            List<DependencyRequest> toolRequests = groups.computeIfAbsent(toolName, ignored -> new ArrayList<>());
            for (ExecToolCoordinate coordinate : step.exec().tool().coordinates()) {
                String version = coordinate.version()
                        .filter(value -> !value.isBlank())
                        .orElseThrow(() -> new ResolveException(
                                "Exec tool `" + toolName + "` coordinate " + coordinate.coordinate()
                                        + " requires a version. Add version or versionRef, run `zolt resolve`, then retry."));
                Coordinate parsed = coordinateParser.parse(coordinate.coordinate() + ":" + version);
                PackageId packageId = PackageId.from(parsed);
                boolean already = toolRequests.stream()
                        .anyMatch(request -> request.packageId().equals(packageId)
                                && request.requestedVersion().equals(version));
                if (already) {
                    continue;
                }
                toolRequests.add(new DependencyRequest(
                        packageId,
                        version,
                        DependencyScope.TOOL_EXEC,
                        RequestOrigin.DIRECT));
            }
        }
        return groups;
    }

    private void addExecToolRequests(
            ProjectConfig config,
            List<DependencyRequest> requests) {
        for (GeneratedSourceStep step : execSteps(config)) {
            for (ExecToolCoordinate coordinate : step.exec().tool().coordinates()) {
                String version = coordinate.version()
                        .filter(value -> !value.isBlank())
                        .orElseThrow(() -> new ResolveException(
                                "Exec tool `" + step.exec().toolName() + "` coordinate " + coordinate.coordinate()
                                        + " requires a version. Add version or versionRef, run `zolt resolve`, then retry."));
                addToolRequest(requests, coordinate.coordinate(), version, DependencyScope.TOOL_EXEC);
            }
        }
    }

    private void addOpenApiToolRequests(
            ProjectConfig config,
            List<DependencyRequest> requests) {
        List<GeneratedSourceStep> steps = openApiSteps(config);
        if (steps.isEmpty()) {
            return;
        }
        OpenApiGenerationSettings settings = steps.getFirst().openApi();
        String coordinate = settings.toolCoordinate()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new ResolveException(
                        "OpenAPI generation requires [generated.tools.openapi].coordinate. "
                                + "Add org.openapitools:openapi-generator-cli with version or versionRef, run `zolt resolve`, then retry."));
        String version = settings.toolVersion()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new ResolveException(
                        "OpenAPI generation requires [generated.tools.openapi].version for "
                                + coordinate
                                + ". Add version or versionRef, run `zolt resolve`, then retry."));
        addToolRequest(requests, coordinate, version, DependencyScope.TOOL_OPENAPI);
    }

    private void addProtobufToolRequests(
            ProjectConfig config,
            List<DependencyRequest> requests) {
        List<GeneratedSourceStep> steps = protobufSteps(config);
        if (steps.isEmpty()) {
            return;
        }
        ProtobufGenerationSettings settings = steps.getFirst().protobuf();
        addProtobufToolRequest(
                requests,
                "Protobuf generation requires [generated.tools.protobuf].protocCoordinate.",
                "Protobuf generation requires [generated.tools.protobuf].protocVersion",
                settings.protocCoordinate(),
                settings.protocVersion());
        if (settings.grpc()) {
            addProtobufToolRequest(
                    requests,
                    "Protobuf gRPC generation requires [generated.tools.protobuf].grpcCoordinate.",
                    "Protobuf gRPC generation requires [generated.tools.protobuf].grpcVersion",
                    settings.grpcPluginCoordinate(),
                    settings.grpcPluginVersion());
        }
    }

    private void addProtobufToolRequest(
            List<DependencyRequest> requests,
            String missingCoordinateMessage,
            String missingVersionMessage,
            Optional<String> coordinateValue,
            Optional<String> versionValue) {
        String coordinate = coordinateValue
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new ResolveException(
                        missingCoordinateMessage
                                + " Add [generated.tools.protobuf] versions, run `zolt resolve`, then retry."));
        String version = versionValue
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new ResolveException(
                        missingVersionMessage
                                + " for "
                                + coordinate
                                + ". Add version or versionRef, run `zolt resolve`, then retry."));
        addToolRequest(requests, coordinate, version, DependencyScope.TOOL_PROTOBUF);
    }

    private void addToolRequest(
            List<DependencyRequest> requests,
            String coordinate,
            String version,
            DependencyScope scope) {
        Coordinate parsed = coordinateParser.parse(coordinate + ":" + version);
        PackageId packageId = PackageId.from(parsed);
        boolean alreadyRequested = requests.stream()
                .anyMatch(request -> request.packageId().equals(packageId)
                        && request.scope() == scope);
        if (alreadyRequested) {
            return;
        }
        requests.add(new DependencyRequest(
                packageId,
                parsed.version().orElseThrow(),
                scope,
                RequestOrigin.DIRECT));
    }

    private static List<GeneratedSourceStep> openApiSteps(ProjectConfig config) {
        List<GeneratedSourceStep> steps = new ArrayList<>();
        config.build().generatedMainSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.OPENAPI)
                .forEach(steps::add);
        config.build().generatedTestSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.OPENAPI)
                .forEach(steps::add);
        return steps;
    }

    private static List<GeneratedSourceStep> protobufSteps(ProjectConfig config) {
        List<GeneratedSourceStep> steps = new ArrayList<>();
        config.build().generatedMainSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.PROTOBUF)
                .forEach(steps::add);
        config.build().generatedTestSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.PROTOBUF)
                .forEach(steps::add);
        return steps;
    }

    private static List<GeneratedSourceStep> execSteps(ProjectConfig config) {
        List<GeneratedSourceStep> steps = new ArrayList<>();
        config.build().generatedMainSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.EXEC)
                .forEach(steps::add);
        config.build().generatedTestSources().stream()
                .filter(step -> step.kind() == GeneratedSourceKind.EXEC)
                .forEach(steps::add);
        return steps;
    }
}
