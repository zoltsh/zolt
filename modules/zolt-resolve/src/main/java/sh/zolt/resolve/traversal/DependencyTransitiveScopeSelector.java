package sh.zolt.resolve.traversal;

import sh.zolt.dependency.DependencyScope;
import java.util.Optional;

final class DependencyTransitiveScopeSelector {
    Optional<DependencyScope> select(DependencyScope parentScope, DependencyScope dependencyScope) {
        if (dependencyScope == DependencyScope.TEST || dependencyScope == DependencyScope.PROVIDED) {
            return Optional.empty();
        }
        if (parentScope == DependencyScope.PROCESSOR) {
            return Optional.of(DependencyScope.PROCESSOR);
        }
        if (parentScope == DependencyScope.TEST_PROCESSOR) {
            return Optional.of(DependencyScope.TEST_PROCESSOR);
        }
        if (parentScope == DependencyScope.QUARKUS_DEPLOYMENT) {
            return Optional.of(DependencyScope.QUARKUS_DEPLOYMENT);
        }
        if (parentScope == DependencyScope.TOOL_SPRING_AOT) {
            return Optional.of(DependencyScope.TOOL_SPRING_AOT);
        }
        if (parentScope == DependencyScope.TOOL_OPENAPI) {
            return Optional.of(DependencyScope.TOOL_OPENAPI);
        }
        if (parentScope == DependencyScope.TOOL_PROTOBUF) {
            return Optional.of(DependencyScope.TOOL_PROTOBUF);
        }
        if (parentScope == DependencyScope.TOOL_EXEC) {
            return Optional.of(DependencyScope.TOOL_EXEC);
        }
        if (parentScope == DependencyScope.TOOL_COVERAGE) {
            return Optional.of(DependencyScope.TOOL_COVERAGE);
        }
        if (parentScope == DependencyScope.TOOL_GROOVY) {
            return Optional.of(DependencyScope.TOOL_GROOVY);
        }
        if (parentScope == DependencyScope.TEST) {
            return Optional.of(DependencyScope.TEST);
        }
        if (parentScope == DependencyScope.RUNTIME) {
            return Optional.of(DependencyScope.RUNTIME);
        }
        if (parentScope == DependencyScope.DEV) {
            return Optional.of(DependencyScope.DEV);
        }
        if (parentScope == DependencyScope.COMPILE) {
            return Optional.of(dependencyScope);
        }
        return Optional.empty();
    }
}
