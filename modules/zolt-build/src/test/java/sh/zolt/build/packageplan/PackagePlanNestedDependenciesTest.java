package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.PackageException;
import sh.zolt.dependency.DependencyScope;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PackagePlanNestedDependenciesTest {
    @Test
    void exactArtifactDuplicatesCollapseToOnePlannedPath() {
        PackagePlanDependency compile =
                dependency(
                        "com.example:shared:1.0.0",
                        DependencyScope.COMPILE,
                        "WEB-INF/lib/shared.jar");
        PackagePlanDependency runtime =
                dependency(
                        "com.example:shared:1.0.0",
                        DependencyScope.RUNTIME,
                        "WEB-INF/lib/shared.jar");

        assertEquals(
                List.of(compile),
                PackagePlanNestedDependencies.canonicalize(
                        List.of(compile, runtime)));
    }

    @Test
    void differentArtifactsCannotClaimTheSamePlannedPath() {
        assertThrows(
                PackageException.class,
                () -> PackagePlanNestedDependencies.canonicalize(List.of(
                        dependency(
                                "com.alpha:shared:1.0.0",
                                DependencyScope.RUNTIME,
                                "WEB-INF/lib/shared.jar"),
                        dependency(
                                "com.beta:shared:1.0.0",
                                DependencyScope.RUNTIME,
                                "WEB-INF/lib/shared.jar"))));
    }

    private static PackagePlanDependency dependency(
            String coordinate,
            DependencyScope scope,
            String location) {
        return new PackagePlanDependency(
                coordinate,
                "1.0.0",
                scope,
                "included",
                "test-rule",
                location,
                "test dependency",
                List.of());
    }
}
