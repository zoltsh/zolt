package sh.zolt.resolve.request.tooling;

import java.util.List;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.request.RequestVersionOrigin;

/** Contributes the configured Kotlin compiler as an isolated build-time tool dependency. */
public final class KotlinToolingDependencyContributor {
    private static final PackageId KOTLIN_COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");

    public void contribute(ProjectConfig config, List<DependencyRequest> requests) {
        String version = config.compilerSettings().kotlinVersion();
        if (version == null || version.isBlank()) {
            return;
        }
        boolean alreadyRequested = requests.stream()
                .anyMatch(request -> request.packageId().equals(KOTLIN_COMPILER)
                        && request.scope() == DependencyScope.TOOL_KOTLIN);
        if (!alreadyRequested) {
            requests.add(new DependencyRequest(
                    KOTLIN_COMPILER,
                    version.strip(),
                    DependencyScope.TOOL_KOTLIN,
                    RequestOrigin.DIRECT,
                    RequestVersionOrigin.DECLARED));
        }
    }
}
