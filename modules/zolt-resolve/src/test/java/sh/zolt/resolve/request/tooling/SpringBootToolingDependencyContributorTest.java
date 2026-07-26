package sh.zolt.resolve.request.tooling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.toml.ZoltTomlParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SpringBootToolingDependencyContributorTest {
    private static final PackageId SPRING_BOOT_LOADER =
            new PackageId("org.springframework.boot", "spring-boot-loader");
    private static final PackageId SPRING_BOOT_AOT_TOOL =
            new PackageId("org.springframework.boot", "spring-boot");

    private final SpringBootToolingDependencyContributor contributor =
            new SpringBootToolingDependencyContributor();

    @Test
    void addsSpringBootLoaderForSpringBootPackageMode() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(
                baseConfig().withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT)),
                Map.of(SPRING_BOOT_LOADER, "4.0.6"),
                requests);

        DependencyRequest request = onlyRequest(requests, SPRING_BOOT_LOADER);
        assertEquals("4.0.6", request.requestedVersion());
        assertEquals(DependencyScope.RUNTIME, request.scope());
        assertEquals(RequestOrigin.TRANSITIVE, request.origin());
    }

    @Test
    void classifiedLoaderRequestDoesNotSuppressDefaultPackageTooling() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                SPRING_BOOT_LOADER,
                "4.0.6",
                DependencyScope.RUNTIME,
                RequestOrigin.DIRECT,
                Optional.of(ArtifactDescriptor.jar(
                        new Coordinate(
                                SPRING_BOOT_LOADER.groupId(),
                                SPRING_BOOT_LOADER.artifactId(),
                                Optional.of("4.0.6")),
                        Optional.of("tests")))));

        contributor.contribute(
                baseConfig().withPackageSettings(
                        new PackageSettings(PackageMode.SPRING_BOOT)),
                Map.of(SPRING_BOOT_LOADER, "4.0.6"),
                requests);

        assertEquals(2, requests.stream()
                .filter(request -> request.packageId().equals(
                        SPRING_BOOT_LOADER))
                .count());
        DependencyRequest defaultLoader = requests.stream()
                .filter(request -> request.packageId().equals(
                        SPRING_BOOT_LOADER))
                .filter(request -> request.artifactVariant().isDefault())
                .findFirst()
                .orElseThrow();
        assertEquals(DependencyScope.RUNTIME, defaultLoader.scope());
        assertEquals(RequestOrigin.TRANSITIVE, defaultLoader.origin());
    }

    @Test
    void providedDefaultLoaderDoesNotSuppressRuntimePackageTooling() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                SPRING_BOOT_LOADER,
                "4.0.6",
                DependencyScope.PROVIDED,
                RequestOrigin.DIRECT));

        contributor.contribute(
                baseConfig().withPackageSettings(
                        new PackageSettings(
                                PackageMode.SPRING_BOOT_WAR)),
                Map.of(SPRING_BOOT_LOADER, "4.0.6"),
                requests);

        assertTrue(requests.stream().anyMatch(request ->
                request.packageId().equals(SPRING_BOOT_LOADER)
                        && request.scope()
                                == DependencyScope.PROVIDED
                        && request.origin() == RequestOrigin.DIRECT));
        assertTrue(requests.stream().anyMatch(request ->
                request.packageId().equals(SPRING_BOOT_LOADER)
                        && request.scope()
                                == DependencyScope.RUNTIME
                        && request.origin()
                                == RequestOrigin.TRANSITIVE));
    }

    @Test
    void reportsMissingSpringBootLoaderManagedVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(
                        baseConfig().withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT)),
                        Map.of(),
                        new ArrayList<>()));

        assertTrue(exception.getMessage().contains("Spring Boot package mode requires package tool artifact"));
        assertTrue(exception.getMessage().contains("Add the Spring Boot platform to [platforms]"));
    }

    @Test
    void addsSpringBootAotToolForNativeMode() {
        List<DependencyRequest> requests = new ArrayList<>();

        contributor.contribute(
                springBootNativeConfig(),
                Map.of(SPRING_BOOT_AOT_TOOL, "4.0.6"),
                requests);

        DependencyRequest request = onlyRequest(requests, SPRING_BOOT_AOT_TOOL);
        assertEquals("4.0.6", request.requestedVersion());
        assertEquals(DependencyScope.TOOL_SPRING_AOT, request.scope());
        assertEquals(RequestOrigin.TRANSITIVE, request.origin());
    }

    @Test
    void reportsMissingSpringBootAotManagedVersionClearly() {
        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> contributor.contribute(
                        springBootNativeConfig(),
                        Map.of(),
                        new ArrayList<>()));

        assertTrue(exception.getMessage().contains("Spring Boot native AOT requires tool artifact"));
        assertTrue(exception.getMessage().contains("Add the Spring Boot platform to [platforms]"));
    }

    @Test
    void doesNotDuplicateAlreadyRequestedSpringBootTools() {
        List<DependencyRequest> requests = new ArrayList<>();
        requests.add(new DependencyRequest(
                SPRING_BOOT_LOADER,
                "4.0.6",
                DependencyScope.RUNTIME,
                RequestOrigin.TRANSITIVE));
        requests.add(new DependencyRequest(
                SPRING_BOOT_AOT_TOOL,
                "4.0.6",
                DependencyScope.TOOL_SPRING_AOT,
                RequestOrigin.TRANSITIVE));

        contributor.contribute(
                springBootNativeConfig().withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT)),
                Map.of(
                        SPRING_BOOT_LOADER,
                        "4.0.6",
                        SPRING_BOOT_AOT_TOOL,
                        "4.0.6"),
                requests);

        assertEquals(1, requests.stream()
                .filter(request -> request.packageId().equals(SPRING_BOOT_LOADER))
                .count());
        assertEquals(1, requests.stream()
                .filter(request -> request.packageId().equals(SPRING_BOOT_AOT_TOOL))
                .count());
    }

    private static DependencyRequest onlyRequest(List<DependencyRequest> requests, PackageId packageId) {
        return requests.stream()
                .filter(request -> request.packageId().equals(packageId))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectConfig baseConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);
    }

    private static ProjectConfig springBootNativeConfig() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "spring-native-demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [framework.springBoot.native]
                enabled = true
                """);
    }
}
