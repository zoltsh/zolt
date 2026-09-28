package sh.zolt.build.packageplan;

import sh.zolt.dependency.DependencyScope;

final class PackagePlanDependencyOmissions {
    private PackagePlanDependencyOmissions() {
    }

    static String reason(DependencyScope scope, boolean springBootWar) {
        if (scope == DependencyScope.PROVIDED && springBootWar) {
            return "provided dependency is placed in WEB-INF/lib-provided";
        }
        return switch (scope) {
            case PROVIDED -> "provided dependency is expected from the servlet/container runtime";
            case DEV -> "dev dependency is excluded from package artifacts";
            case TEST -> "test dependency is excluded from main package artifacts";
            case PROCESSOR, TEST_PROCESSOR -> "annotation processor dependency is excluded from package artifacts";
            case QUARKUS_DEPLOYMENT -> "Quarkus deployment dependency is build-time tooling, not package runtime";
            case TOOL_SPRING_AOT -> "Spring Boot AOT dependency is build-time tooling, not package runtime";
            case TOOL_OPENAPI -> "OpenAPI generator dependency is build-time tooling, not package runtime";
            case TOOL_PROTOBUF -> "Protobuf generator dependency is build-time tooling, not package runtime";
            case TOOL_EXEC -> "exec tool dependency is build-time tooling, not package runtime";
            case TOOL_COVERAGE -> "coverage dependency is build-time tooling, not package runtime";
            case TOOL_GROOVY -> "Groovy compiler dependency is build-time tooling, not package runtime";
            case COMPILE, RUNTIME -> "dependency scope is not packaged by this mode";
        };
    }

    static String rule(DependencyScope scope, boolean springBootWar) {
        if (scope == DependencyScope.PROVIDED && springBootWar) {
            return "spring-boot-war-provided-lib";
        }
        return switch (scope) {
            case PROVIDED -> "provided-container-omitted";
            case DEV -> "dev-only-omitted";
            case TEST -> "test-omitted";
            case PROCESSOR, TEST_PROCESSOR -> "processor-omitted";
            case QUARKUS_DEPLOYMENT -> "quarkus-deployment-omitted";
            case TOOL_SPRING_AOT -> "spring-aot-tool-omitted";
            case TOOL_OPENAPI -> "openapi-tool-omitted";
            case TOOL_PROTOBUF -> "protobuf-tool-omitted";
            case TOOL_EXEC -> "exec-tool-omitted";
            case TOOL_COVERAGE -> "coverage-tool-omitted";
            case TOOL_GROOVY -> "groovy-tool-omitted";
            case COMPILE, RUNTIME -> "non-runtime-omitted";
        };
    }
}
