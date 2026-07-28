package sh.zolt.resolve.request.tooling;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.request.RequestVersionOrigin;
import java.util.List;
import java.util.Map;

public final class SpringBootToolingDependencyContributor {
    private static final PackageId SPRING_BOOT_LOADER_PACKAGE =
            SpringBootLoaderArtifact.PACKAGE_ID;
    private static final PackageId SPRING_BOOT_AOT_TOOL_PACKAGE = new PackageId(
            "org.springframework.boot",
            "spring-boot");

    public void contribute(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            List<DependencyRequest> requests) {
        addPackageModeRequests(config, projectManagedVersions, requests);
        addSpringBootAotToolRequests(config, projectManagedVersions, requests);
    }

    private void addPackageModeRequests(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            List<DependencyRequest> requests) {
        if (!isSpringBootArchive(config.packageSettings().mode())) {
            return;
        }
        boolean loaderAlreadyOnMainRuntimeClasspath = requests.stream()
                .anyMatch(request -> SpringBootLoaderArtifact.isDefaultLoader(
                                request.packageId(),
                                request.artifactVariant())
                        && request.scope().entersMainRuntimeClasspath());
        if (loaderAlreadyOnMainRuntimeClasspath) {
            return;
        }
        String version = projectManagedVersions.get(SPRING_BOOT_LOADER_PACKAGE);
        if (version == null || version.isBlank()) {
            throw new ResolveException(
                    "Spring Boot package mode requires package tool artifact `org.springframework.boot:spring-boot-loader`, "
                            + "but no declared [platforms] entry manages its version. Add the Spring Boot platform to [platforms] "
                            + "or declare `org.springframework.boot:spring-boot-loader` with an explicit version, then run `zolt resolve`.");
        }
        requests.add(new DependencyRequest(
                SPRING_BOOT_LOADER_PACKAGE,
                version,
                DependencyScope.RUNTIME,
                RequestOrigin.TRANSITIVE,
                RequestVersionOrigin.MANAGED));
    }

    private void addSpringBootAotToolRequests(
            ProjectConfig config,
            Map<PackageId, String> projectManagedVersions,
            List<DependencyRequest> requests) {
        if (!config.frameworkSettings().springBoot().nativeEnabled()) {
            return;
        }
        boolean alreadyRequested = requests.stream()
                .anyMatch(request -> request.packageId().equals(SPRING_BOOT_AOT_TOOL_PACKAGE)
                        && request.scope() == DependencyScope.TOOL_SPRING_AOT);
        if (alreadyRequested) {
            return;
        }
        String version = projectManagedVersions.get(SPRING_BOOT_AOT_TOOL_PACKAGE);
        if (version == null || version.isBlank()) {
            throw new ResolveException(
                    "Spring Boot native AOT requires tool artifact `org.springframework.boot:spring-boot`, "
                            + "but no declared [platforms] entry manages its version. Add the Spring Boot platform to [platforms], "
                            + "then run `zolt resolve`.");
        }
        requests.add(new DependencyRequest(
                SPRING_BOOT_AOT_TOOL_PACKAGE,
                version,
                DependencyScope.TOOL_SPRING_AOT,
                RequestOrigin.TRANSITIVE,
                RequestVersionOrigin.MANAGED));
    }

    private static boolean isSpringBootArchive(PackageMode mode) {
        return mode == PackageMode.SPRING_BOOT || mode == PackageMode.SPRING_BOOT_WAR;
    }
}
