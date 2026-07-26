package sh.zolt.build.springboot;

import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageRuntimeJar;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.SpringBootLoaderArtifact;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class SpringBootLoaderSupport {
    static final PackageId SPRING_BOOT_PACKAGE = new PackageId("org.springframework.boot", "spring-boot");
    static final PackageId SPRING_BOOT_LOADER_PACKAGE =
            SpringBootLoaderArtifact.PACKAGE_ID;

    private static final String BOOT_LOADER_PREFIX = "org/springframework/boot/loader/";
    private static final String BOOT_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher";
    private static final String BOOT_WAR_LAUNCHER = "org.springframework.boot.loader.launch.WarLauncher";
    private static final String LEGACY_BOOT_LAUNCHER = "org.springframework.boot.loader.JarLauncher";
    private static final String LEGACY_BOOT_WAR_LAUNCHER = "org.springframework.boot.loader.WarLauncher";

    private SpringBootLoaderSupport() {
    }

    static SpringBootLoader jarLoader(List<PackageRuntimeJar> runtimeJars) {
        return loader(runtimeJars, false);
    }

    static SpringBootLoader warLoader(List<PackageRuntimeJar> runtimeJars) {
        return loader(runtimeJars, true);
    }

    private static SpringBootLoader loader(List<PackageRuntimeJar> runtimeJars, boolean war) {
        PackageRuntimeJar loaderJar = runtimeJars.stream()
                .filter(runtimeJar -> SpringBootLoaderArtifact.isDefaultLoader(
                        runtimeJar.artifactIdentity().packageId(),
                        runtimeJar.artifactIdentity().extension(),
                        runtimeJar.artifactIdentity().classifier()))
                .findFirst()
                .orElseThrow(() -> new PackageException(missingSpringBootLoaderMessage(runtimeJars)));
        Map<String, byte[]> entries;
        try {
            entries = loaderEntries(loaderJar.jarPath());
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not read Spring Boot loader jar at "
                            + loaderJar.jarPath()
                            + ". Run `zolt resolve` to refresh the artifact cache and retry.",
                    exception);
        }
        if (entries.isEmpty()) {
            throw new PackageException(
                    "Spring Boot loader jar at "
                            + loaderJar.jarPath()
                            + " does not contain "
                            + BOOT_LOADER_PREFIX
                            + " classes. Check the resolved org.springframework.boot:spring-boot-loader artifact.");
        }
        String launcherClass = war ? warLauncherClass(entries) : launcherClass(entries);
        return new SpringBootLoader(loaderJar, launcherClass, entries);
    }

    private static String missingSpringBootLoaderMessage(List<PackageRuntimeJar> runtimeJars) {
        String versionHint = runtimeJars.stream()
                .filter(runtimeJar -> runtimeJar.packageId().equals(SPRING_BOOT_PACKAGE))
                .map(PackageRuntimeJar::version)
                .findFirst()
                .map(version -> " The resolved Spring Boot version appears to be " + version + ".")
                .orElse("");
        return "Spring Boot package mode requires `org.springframework.boot:spring-boot-loader` in zolt.lock. Add the Spring Boot platform to [platforms] so Zolt can resolve the loader as package tooling, or declare the loader with an explicit version, then run `zolt resolve` and retry."
                + versionHint;
    }

    private static Map<String, byte[]> loaderEntries(Path loaderJar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(loaderJar.toFile())) {
            List<JarEntry> loaderEntries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().startsWith(BOOT_LOADER_PREFIX))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList();
            for (JarEntry entry : loaderEntries) {
                try (var input = jar.getInputStream(entry)) {
                    entries.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static String launcherClass(Map<String, byte[]> loaderEntries) {
        if (loaderEntries.containsKey(classEntryName(BOOT_LAUNCHER))) {
            return BOOT_LAUNCHER;
        }
        if (loaderEntries.containsKey(classEntryName(LEGACY_BOOT_LAUNCHER))) {
            return LEGACY_BOOT_LAUNCHER;
        }
        throw new PackageException(
                "Spring Boot loader classes were found, but JarLauncher is missing. Expected "
                        + BOOT_LAUNCHER
                        + " or "
                        + LEGACY_BOOT_LAUNCHER
                        + ".");
    }

    private static String warLauncherClass(Map<String, byte[]> loaderEntries) {
        if (loaderEntries.containsKey(classEntryName(BOOT_WAR_LAUNCHER))) {
            return BOOT_WAR_LAUNCHER;
        }
        if (loaderEntries.containsKey(classEntryName(LEGACY_BOOT_WAR_LAUNCHER))) {
            return LEGACY_BOOT_WAR_LAUNCHER;
        }
        throw new PackageException(
                "Spring Boot loader classes were found, but WarLauncher is missing. Expected "
                        + BOOT_WAR_LAUNCHER
                        + " or "
                        + LEGACY_BOOT_WAR_LAUNCHER
                        + ".");
    }

    private static String classEntryName(String className) {
        return className.replace('.', '/') + ".class";
    }

    record SpringBootLoader(PackageRuntimeJar jar, String launcherClass, Map<String, byte[]> entries) {
    }
}
