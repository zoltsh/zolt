package sh.zolt.resolve.materialization.session;

import sh.zolt.concurrent.RepositoryExecutionLane;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.materialization.MaterializedArtifact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

final class ArtifactBatchMaterializer {
    Map<ArtifactDescriptor, MaterializedArtifact> materialize(
            List<ArtifactDescriptor> descriptors,
            int concurrency,
            Function<ArtifactDescriptor, MaterializedArtifact> materializer) {
        return materialize(descriptors, concurrency, RepositoryExecutionLane.DEFAULT, materializer);
    }

    Map<ArtifactDescriptor, MaterializedArtifact> materialize(
            List<ArtifactDescriptor> descriptors,
            int concurrency,
            RepositoryExecutionLane executionLane,
            Function<ArtifactDescriptor, MaterializedArtifact> materializer) {
        Map<ArtifactDescriptor, ArtifactDescriptor> uniqueDescriptors = new LinkedHashMap<>();
        descriptors.stream()
                .sorted(Comparator.comparing(ArtifactBatchMaterializer::artifactDescriptorKey))
                .forEach(descriptor -> uniqueDescriptors.putIfAbsent(descriptor, descriptor));
        if (uniqueDescriptors.isEmpty()) {
            return Map.of();
        }
        try (ExecutorService executor = executionLane.openExecutor(concurrency)) {
            Map<ArtifactDescriptor, Future<MaterializedArtifact>> futures = new LinkedHashMap<>();
            for (ArtifactDescriptor descriptor : uniqueDescriptors.values()) {
                futures.put(descriptor, executor.submit(() -> materializer.apply(descriptor)));
            }

            Map<ArtifactDescriptor, MaterializedArtifact> artifacts = new LinkedHashMap<>();
            List<ArtifactDownloadFailure> failures = new ArrayList<>();
            for (Map.Entry<ArtifactDescriptor, Future<MaterializedArtifact>> entry : futures.entrySet()) {
                try {
                    artifacts.put(entry.getKey(), entry.getValue().get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failures.add(new ArtifactDownloadFailure(
                            artifactDescriptorKey(entry.getKey()),
                            "interrupted while materializing artifact"));
                } catch (ExecutionException exception) {
                    failures.add(new ArtifactDownloadFailure(
                            artifactDescriptorKey(entry.getKey()),
                            failureMessage(exception.getCause())));
                }
            }
            if (!failures.isEmpty()) {
                throw artifactDownloadException(failures);
            }
            return artifacts;
        }
    }

    private static String artifactDescriptorKey(ArtifactDescriptor descriptor) {
        return descriptor.coordinate()
                + ":"
                + descriptor.classifier().orElse("")
                + ":"
                + descriptor.extension();
    }

    private static ResolveException artifactDownloadException(List<ArtifactDownloadFailure> failures) {
        List<ArtifactDownloadFailure> sorted = failures.stream()
                .sorted(Comparator.comparing(ArtifactDownloadFailure::artifactKey))
                .toList();
        StringBuilder summary = new StringBuilder("Selected artifact downloads failed:");
        for (ArtifactDownloadFailure failure : sorted) {
            summary.append(System.lineSeparator())
                    .append("- ")
                    .append(failure.artifactKey())
                    .append(": ")
                    .append(failure.message());
        }
        return ResolveException.actionable(
                summary.toString(),
                "Retry the command or check your repository and network settings.");
    }

    private static String failureMessage(Throwable cause) {
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return cause == null ? "download failed" : cause.getClass().getSimpleName();
        }
        return cause.getMessage();
    }

    private record ArtifactDownloadFailure(String artifactKey, String message) {
    }
}
