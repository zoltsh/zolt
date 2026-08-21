package sh.zolt.workspace.service;

import static sh.zolt.workspace.WorkspaceContentAddressedLockTestSupport.dependencyRoot;

import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;

final class WorkspaceClasspathRootFixtures {
    private WorkspaceClasspathRootFixtures() {}

    static String memberClosure() {
        return dependencyRoot("apps/api", "com.acme:core", "0.1.0", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)
                + dependencyRoot("apps/worker", "com.acme:extra", "0.1.0", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)
                + dependencyRoot("modules/core", "org.example:core-helper", "1.0.0", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)
                + dependencyRoot("modules/core", "org.example:core-api", "1.0.0", DependencyLane.API, DependencyScope.COMPILE)
                + dependencyRoot("apps/worker", "org.example:worker-helper", "1.0.0", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)
                + dependencyRoot(".", "org.example:legacy", "1.0.0", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE);
    }

    static String processorClosure() {
        return dependencyRoot("apps/api", "com.acme:processor", "0.1.0", DependencyLane.PROCESSOR, DependencyScope.PROCESSOR)
                + dependencyRoot("modules/processor", "com.acme:helper", "0.1.0", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE)
                + dependencyRoot("apps/api", "com.acme:test-processor", "0.1.0", DependencyLane.TEST_PROCESSOR, DependencyScope.TEST_PROCESSOR)
                + dependencyRoot("apps/api", "org.example:api-processor", "1.0.0", DependencyLane.PROCESSOR, DependencyScope.PROCESSOR);
    }

    static String classifiedRuntime() {
        return classified("apps/api", "linux-x86_64", DependencyLane.RUNTIME, DependencyScope.RUNTIME)
                + classified("apps/worker", "osx-aarch_64", DependencyLane.RUNTIME, DependencyScope.RUNTIME);
    }

    static String classifiedApi() {
        return classified("modules/core", "linux-x86_64", DependencyLane.API, DependencyScope.COMPILE);
    }

    static String apiClosure() {
        return dependencyRoot("modules/core", "com.example:api-lib", "1.0.0", DependencyLane.API, DependencyScope.COMPILE);
    }

    private static String classified(
            String member,
            String classifier,
            DependencyLane lane,
            DependencyScope scope) {
        return dependencyRoot(
                member,
                "io.netty:netty-transport-native-epoll",
                "4.1.100.Final",
                new LockArtifactVariant("jar", Optional.of(classifier)),
                lane,
                scope);
    }
}
