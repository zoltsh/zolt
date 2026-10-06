package sh.zolt.resolve.request.tooling;

import java.util.List;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.request.RequestVersionOrigin;

/** Contributes the configured Kotlin compiler as an isolated build-time tool dependency. */
public final class KotlinToolingDependencyContributor {
    private static final PackageId KOTLIN_COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");
    private static final PackageId KOTLIN_KAPT =
            new PackageId("org.jetbrains.kotlin", "kotlin-annotation-processing-embeddable");
    private static final PackageId KOTLIN_SERIALIZATION = new PackageId(
            "org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable");
    private static final PackageId KOTLIN_ALL_OPEN = new PackageId(
            "org.jetbrains.kotlin", "kotlin-allopen-compiler-plugin-embeddable");

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
        if (!config.annotationProcessors().isEmpty()
                || !config.testAnnotationProcessors().isEmpty()) {
            contributeKapt(version.strip(), requests);
        }
        if (config.compilerSettings().kotlinPlugins()
                .contains(KotlinCompilerPlugin.SERIALIZATION)) {
            contribute(version.strip(), KOTLIN_SERIALIZATION, requests);
        }
        if (config.compilerSettings().kotlinPlugins()
                .contains(KotlinCompilerPlugin.SPRING)) {
            contribute(version.strip(), KOTLIN_ALL_OPEN, requests);
        }
    }

    private static void contributeKapt(
            String version,
            List<DependencyRequest> requests) {
        contribute(version, KOTLIN_KAPT, requests);
    }

    private static void contribute(
            String version,
            PackageId packageId,
            List<DependencyRequest> requests) {
        boolean alreadyRequested = requests.stream()
                .anyMatch(request -> request.packageId().equals(packageId)
                        && request.scope() == DependencyScope.TOOL_KOTLIN);
        if (alreadyRequested) {
            return;
        }
        requests.add(new DependencyRequest(
                packageId,
                version,
                DependencyScope.TOOL_KOTLIN,
                RequestOrigin.DIRECT,
                RequestVersionOrigin.DECLARED));
    }
}
