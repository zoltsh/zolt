package sh.zolt.project;

import java.util.List;

/** Language-specific source roots for the unit-test source set. */
public record TestSourceRoots(
        List<String> javaSources,
        List<String> groovySources,
        List<String> kotlinSources) {
    public TestSourceRoots {
        javaSources = copy(javaSources);
        groovySources = copy(groovySources);
        kotlinSources = copy(kotlinSources);
    }

    static TestSourceRoots from(
            String defaultJavaSource,
            List<String> javaSources,
            List<String> groovySources,
            List<String> kotlinSources) {
        return new TestSourceRoots(
                javaSources == null ? List.of(defaultJavaSource) : javaSources,
                groovySources,
                kotlinSources);
    }

    static TestSourceRoots defaults(String defaultJavaSource) {
        return from(defaultJavaSource, null, null, null);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
