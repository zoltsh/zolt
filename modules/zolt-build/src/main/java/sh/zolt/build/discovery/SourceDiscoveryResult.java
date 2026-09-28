package sh.zolt.build.discovery;

import java.nio.file.Path;
import java.util.List;

public record SourceDiscoveryResult(
        List<Path> mainSources,
        List<Path> groovyMainSources,
        List<Path> testSources,
        List<Path> groovyTestSources) {
    public SourceDiscoveryResult(List<Path> mainSources, List<Path> testSources) {
        this(mainSources, List.of(), testSources, List.of());
    }

    public SourceDiscoveryResult(
            List<Path> mainSources,
            List<Path> testSources,
            List<Path> groovyTestSources) {
        this(mainSources, List.of(), testSources, groovyTestSources);
    }

    public SourceDiscoveryResult {
        mainSources = List.copyOf(mainSources);
        groovyMainSources = List.copyOf(groovyMainSources);
        testSources = List.copyOf(testSources);
        groovyTestSources = List.copyOf(groovyTestSources);
    }

    public boolean empty() {
        return mainSources.isEmpty()
                && groovyMainSources.isEmpty()
                && testSources.isEmpty()
                && groovyTestSources.isEmpty();
    }

    public List<Path> allMainSources() {
        return java.util.stream.Stream.concat(mainSources.stream(), groovyMainSources.stream())
                .sorted()
                .toList();
    }

    public List<Path> allTestSources() {
        return java.util.stream.Stream.concat(testSources.stream(), groovyTestSources.stream())
                .sorted()
                .toList();
    }
}
