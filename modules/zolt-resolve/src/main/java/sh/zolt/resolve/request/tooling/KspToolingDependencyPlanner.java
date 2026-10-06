package sh.zolt.resolve.request.tooling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;

/** Plans separately mediated KSP engine and processor tool closures. */
final class KspToolingDependencyPlanner {
    private final CoordinateParser coordinateParser;

    KspToolingDependencyPlanner(CoordinateParser coordinateParser) {
        this.coordinateParser = coordinateParser;
    }

    Map<String, List<DependencyRequest>> groups(List<KspGenerationSettings> settings) {
        List<KspGenerationSettings> inputs = settings == null ? List.of() : List.copyOf(settings);
        Map<String, ToolContract> contracts = new LinkedHashMap<>();
        Map<String, List<DependencyRequest>> groups = new TreeMap<>();
        for (KspGenerationSettings input : inputs) {
            if (!input.configured()) {
                continue;
            }
            ToolContract contract = new ToolContract(
                    input.version().orElseThrow(), input.processors());
            ToolContract existing = contracts.putIfAbsent(input.toolName(), contract);
            if (existing != null && !existing.equals(contract)) {
                throw new ResolveException(
                        "KSP tool `" + input.toolName()
                                + "` has inconsistent engine or processor requests across generated steps.");
            }
            if (existing != null) {
                continue;
            }
            groups.put(
                    input.engineGroup(),
                    List.of(request(KspGenerationSettings.ENGINE_COORDINATE, contract.version())));
            List<DependencyRequest> processors = new ArrayList<>(contract.processors().size());
            for (KspProcessorSettings processor : contract.processors()) {
                processors.add(request(processor.coordinate(), processor.version()));
            }
            groups.put(input.processorGroup(), List.copyOf(processors));
        }
        return Collections.unmodifiableMap(groups);
    }

    private DependencyRequest request(String coordinate, String version) {
        Coordinate parsed = coordinateParser.parse(coordinate + ":" + version);
        return new DependencyRequest(
                PackageId.from(parsed),
                parsed.version().orElseThrow(),
                DependencyScope.TOOL_EXEC,
                RequestOrigin.DIRECT);
    }

    private record ToolContract(String version, List<KspProcessorSettings> processors) {
        private ToolContract {
            processors = List.copyOf(processors);
        }
    }
}
