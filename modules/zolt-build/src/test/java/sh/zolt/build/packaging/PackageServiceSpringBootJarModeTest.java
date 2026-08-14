package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.buildSettingsWithMetadata;
import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.createJarWithEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.nestedJarName;
import static sh.zolt.build.packaging.PackageServiceTestSupport.readEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.resourceFilteringSettings;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceSpringBootJarModeTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void packagesExecutableJarLayout() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path springBootJar = cacheRoot.resolve("org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar");
        Path loaderJar = cacheRoot.resolve(
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar");
        Path dependencyJar = cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        Path devJar = cacheRoot.resolve("com/example/devtools/1.0.0/devtools-1.0.0.jar");
        Path processorJar = cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar");
        createJarWithEntry(springBootJar, "org/springframework/boot/SpringApplication.class");
        createJarWithEntry(loaderJar, "org/springframework/boot/loader/launch/JarLauncher.class");
        createJarWithEntry(dependencyJar, "com/example/runtime/RuntimeLib.class");
        createJarWithEntry(devJar, "com/example/dev/DevTools.class");
        createJarWithEntry(processorJar, "com/example/processor/Processor.class");
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 1

                [[package]]
                id = "org.springframework.boot:spring-boot"
                version = "4.0.6"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar"
                dependencies = []

                [[package]]
                id = "org.springframework.boot:spring-boot-loader"
                version = "4.0.6"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar"
                dependencies = []

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:devtools"
                version = "1.0.0"
                source = "maven-central"
                scope = "dev"
                direct = true
                jar = "com/example/devtools/1.0.0/devtools-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        source(projectDir, "src/main/resources/application.properties", "server.port=@serverPort@\nname=@projectName@\n");
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withBuildSettings(buildSettingsWithMetadata(new BuildMetadataSettings(true, false, true)))
                .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT));
        config = config.withBuildSettings(config.build().withResourceFiltering(resourceFilteringSettings()));

        PackageResult result = packageService.packageJar(projectDir, config, cacheRoot);

        assertEquals(PackageMode.SPRING_BOOT, result.mode());
        assertEquals(Optional.empty(), result.runtimeClasspathPath());
        assertTrue(result.hasMainClass());
        String runtimeNestedName = nestedJarName("com.example", "runtime-lib", "1.0.0");
        String springBootNestedName =
                nestedJarName("org.springframework.boot", "spring-boot", "4.0.6");
        String loaderNestedName =
                nestedJarName("org.springframework.boot", "spring-boot-loader", "4.0.6");
        String devNestedName = nestedJarName("com.example", "devtools", "1.0.0");
        String processorNestedName = nestedJarName("com.example", "processor", "1.0.0");
        try (JarFile jar = new JarFile(result.jarPath().toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("1.0", attributes.getValue(Attributes.Name.MANIFEST_VERSION));
            assertEquals(
                    "org.springframework.boot.loader.launch.JarLauncher",
                    attributes.getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("com.example.Main", attributes.getValue("Start-Class"));
            assertEquals("4.0.6", attributes.getValue("Spring-Boot-Version"));
            assertEquals("BOOT-INF/classes/", attributes.getValue("Spring-Boot-Classes"));
            assertEquals("BOOT-INF/lib/", attributes.getValue("Spring-Boot-Lib"));
            assertNotNull(jar.getEntry("org/springframework/boot/loader/launch/JarLauncher.class"));
            assertNotNull(jar.getEntry("BOOT-INF/"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/com/"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/com/example/"));
            assertNotNull(jar.getEntry("BOOT-INF/lib/"));
            assertNotNull(jar.getEntry("org/"));
            assertNotNull(jar.getEntry("org/springframework/"));
            assertNotNull(jar.getEntry("org/springframework/boot/loader/launch/"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/com/example/Main.class"));
            assertEquals("server.port=0\nname=demo\n", readEntry(jar, "BOOT-INF/classes/application.properties"));
            assertEquals("""
                    build.artifact=demo
                    build.group=com.example
                    build.name=demo
                    build.time=1970-01-01T00:00:00Z
                    build.version=0.1.0
                    """, readEntry(jar, "BOOT-INF/classes/META-INF/build-info.properties"));
            assertNotNull(jar.getEntry("BOOT-INF/lib/" + runtimeNestedName));
            assertNotNull(jar.getEntry("BOOT-INF/lib/" + springBootNestedName));
            assertEquals(1, jar.stream()
                    .filter(entry -> entry.getName().equals("BOOT-INF/lib/" + runtimeNestedName))
                    .count());
            assertEquals(
                    JarEntry.STORED,
                    jar.getEntry("BOOT-INF/lib/" + runtimeNestedName).getMethod());
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "BOOT-INF/lib/" + loaderNestedName)));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "BOOT-INF/lib/" + devNestedName)));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "BOOT-INF/lib/" + processorNestedName)));
        }
    }

}
