package sh.zolt.build.discovery;

import java.nio.file.Path;
import java.util.List;

public record SourceDiscoveryResult(
        List<Path> mainSources,
        List<Path> groovyMainSources,
        List<Path> kotlinMainSources,
        List<Path> testSources,
        List<Path> groovyTestSources,
        List<Path> kotlinTestSources) {
    public SourceDiscoveryResult(List<Path> mainSources, List<Path> testSources) {
        this(mainSources, List.of(), List.of(), testSources, List.of(), List.of());
    }

    public SourceDiscoveryResult(
            List<Path> mainSources,
            List<Path> testSources,
            List<Path> groovyTestSources) {
        this(mainSources, List.of(), List.of(), testSources, groovyTestSources, List.of());
    }

    public SourceDiscoveryResult(
            List<Path> mainSources,
            List<Path> groovyMainSources,
            List<Path> testSources,
            List<Path> groovyTestSources) {
        this(mainSources, groovyMainSources, List.of(), testSources, groovyTestSources, List.of());
    }

    public SourceDiscoveryResult {
        mainSources = List.copyOf(mainSources);
        groovyMainSources = List.copyOf(groovyMainSources);
        kotlinMainSources = List.copyOf(kotlinMainSources);
        testSources = List.copyOf(testSources);
        groovyTestSources = List.copyOf(groovyTestSources);
        kotlinTestSources = List.copyOf(kotlinTestSources);
    }

    public boolean empty() {
        return mainSources.isEmpty()
                && groovyMainSources.isEmpty()
                && kotlinMainSources.isEmpty()
                && testSources.isEmpty()
                && groovyTestSources.isEmpty()
                && kotlinTestSources.isEmpty();
    }

    public List<Path> allMainSources() {
        return java.util.stream.Stream.of(mainSources, groovyMainSources, kotlinMainSources)
                .flatMap(List::stream)
                .sorted()
                .toList();
    }

    public List<Path> allTestSources() {
        return java.util.stream.Stream.of(testSources, groovyTestSources, kotlinTestSources)
                .flatMap(List::stream)
                .sorted()
                .toList();
    }
}
