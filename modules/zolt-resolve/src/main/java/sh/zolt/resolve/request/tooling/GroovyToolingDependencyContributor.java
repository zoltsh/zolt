package sh.zolt.resolve.request.tooling;

import java.util.List;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;

/** Contributes the exact Groovy compiler artifact selected by {@code [toolchain.groovy]}. */
public final class GroovyToolingDependencyContributor {
    private static final PackageId GROOVY_COMPILER =
            new PackageId("org.apache.groovy", "groovy");

    public void contribute(
            ProjectConfig config,
            List<DependencyRequest> requests) {
        String version = config.compilerSettings().groovyVersion().strip();
        if (version.isEmpty()) {
            return;
        }
        boolean alreadyRequested = requests.stream()
                .anyMatch(request -> request.packageId().equals(GROOVY_COMPILER)
                        && request.scope() == DependencyScope.TOOL_GROOVY);
        if (alreadyRequested) {
            return;
        }
        requests.add(new DependencyRequest(
                GROOVY_COMPILER,
                version,
                DependencyScope.TOOL_GROOVY,
                RequestOrigin.DIRECT));
    }
}
