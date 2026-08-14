package sh.zolt.build.nativeimage;

import java.util.Optional;
import java.util.stream.Stream;
import sh.zolt.build.NativeImageException;
import sh.zolt.build.springboot.SpringBootNativeBoundaryDiagnostics;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;

final class NativeFrameworkPolicy {
    private static final String SPRING_BOOT_GROUP = "org.springframework.boot";
    private static final String MICRONAUT_GROUP = "io.micronaut";

    private NativeFrameworkPolicy() {}

    static void rejectUnsupported(ProjectConfig config) {
        if (!config.frameworkSettings().springBoot().nativeEnabled() && springBootProject(config)) {
            throw new NativeImageException(
                    "Spring Boot native images require `[framework.springBoot.native] enabled = true`. "
                            + "Zolt supports Spring Boot JVM build, test, run, and executable packaging, "
                            + "and supports an explicit Zolt-owned Spring Boot AOT/native canary path when that flag is enabled. "
                            + "Use `zolt package --mode spring-boot` or `zolt run` for JVM apps, or enable the typed Spring Boot native path.");
        }
        if (config.frameworkSettings().springBoot().nativeEnabled()) {
            rejectUnsupportedSpringBootBaseline(config);
        }
        if (micronautProject(config)) {
            throw new NativeImageException(
                    "Micronaut native images are not supported by Zolt yet. "
                            + "Zolt supports basic Micronaut JVM build/test flows through Java annotation processors, "
                            + "but does not run Micronaut AOT or framework-native processing in the public beta. "
                            + "Use `zolt build`, `zolt test`, or `zolt package --mode thin` for the current beta path.");
        }
        if (quarkusProject(config)) {
            throw new NativeImageException(
                    "Quarkus native images are not supported by Zolt yet. "
                            + "Zolt supports the experimental Quarkus JVM build/test/package path, "
                            + "but does not run Quarkus native augmentation, dev mode, or advanced native modes in the public beta. "
                            + "Use `zolt package --mode quarkus` or `zolt run` for the current beta path.");
        }
    }

    private static void rejectUnsupportedSpringBootBaseline(ProjectConfig config) {
        if (!"21".equals(config.project().java())) {
            throw new NativeImageException(
                    "Spring Boot native support is currently proven for Java 21 projects. Found [project].java = "
                            + config.project().java()
                            + ". Set [project].java to 21 or use `zolt package --mode spring-boot` for the JVM Spring Boot path.");
        }
        springBootVersion(config).ifPresent(version -> {
            if (!version.startsWith("3.3.")) {
                throw new NativeImageException(
                        "Spring Boot native support is currently proven for Spring Boot 3.3 on Java 21. Found Spring Boot "
                                + version
                                + ". Use Spring Boot 3.3 or keep this project on the JVM Spring Boot path until this baseline has executable smoke evidence.");
            }
        });
        SpringBootNativeBoundaryDiagnostics.rejectUnsupportedEcosystem(config);
    }

    private static boolean springBootProject(ProjectConfig config) {
        PackageMode packageMode = config.packageSettings().mode();
        if (packageMode == PackageMode.SPRING_BOOT || packageMode == PackageMode.SPRING_BOOT_WAR) {
            return true;
        }
        if (containsCoordinate(config.platforms().keySet(), SPRING_BOOT_GROUP)) {
            return true;
        }
        return containsSpringBootDependency(config);
    }

    private static boolean containsSpringBootDependency(ProjectConfig config) {
        return containsCoordinate(config.apiDependencies().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedApiDependencies(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.dependencies().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedDependencies(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.runtimeDependencies().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedRuntimeDependencies(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.providedDependencies().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedProvidedDependencies(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.devDependencies().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedDevDependencies(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.testDependencies().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedTestDependencies(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.annotationProcessors().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedAnnotationProcessors(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.testAnnotationProcessors().keySet(), SPRING_BOOT_GROUP)
                || containsCoordinate(config.managedTestAnnotationProcessors(), SPRING_BOOT_GROUP);
    }

    private static Optional<String> springBootVersion(ProjectConfig config) {
        String platformVersion = config.platforms().get("org.springframework.boot:spring-boot-dependencies");
        if (platformVersion != null && !platformVersion.isBlank()) {
            return Optional.of(platformVersion);
        }
        return Stream.of(
                        config.apiDependencies(),
                        config.dependencies(),
                        config.runtimeDependencies(),
                        config.providedDependencies(),
                        config.devDependencies(),
                        config.testDependencies(),
                        config.annotationProcessors(),
                        config.testAnnotationProcessors())
                .flatMap(map -> map.entrySet().stream())
                .filter(entry -> entry.getKey().startsWith(SPRING_BOOT_GROUP + ":"))
                .map(java.util.Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static boolean micronautProject(ProjectConfig config) {
        return containsCoordinate(config.platforms().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.apiDependencies().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedApiDependencies(), MICRONAUT_GROUP)
                || containsCoordinate(config.dependencies().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedDependencies(), MICRONAUT_GROUP)
                || containsCoordinate(config.runtimeDependencies().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedRuntimeDependencies(), MICRONAUT_GROUP)
                || containsCoordinate(config.providedDependencies().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedProvidedDependencies(), MICRONAUT_GROUP)
                || containsCoordinate(config.devDependencies().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedDevDependencies(), MICRONAUT_GROUP)
                || containsCoordinate(config.testDependencies().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedTestDependencies(), MICRONAUT_GROUP)
                || containsCoordinate(config.annotationProcessors().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedAnnotationProcessors(), MICRONAUT_GROUP)
                || containsCoordinate(config.testAnnotationProcessors().keySet(), MICRONAUT_GROUP)
                || containsCoordinate(config.managedTestAnnotationProcessors(), MICRONAUT_GROUP);
    }

    private static boolean quarkusProject(ProjectConfig config) {
        return config.packageSettings().mode() == PackageMode.QUARKUS
                || config.frameworkSettings().quarkus().enabled();
    }

    private static boolean containsCoordinate(Iterable<String> coordinates, String group) {
        for (String coordinate : coordinates) {
            if (coordinate != null && coordinate.startsWith(group + ":")) {
                return true;
            }
        }
        return false;
    }
}
