package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.createJarWithEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.nestedJarName;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceSpringBootWarModeTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void packagesSpringBootWarLayoutWithProvidedDependencies() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path springBootJar = cacheRoot.resolve("org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar");
        Path loaderJar = cacheRoot.resolve(
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar");
        Path runtimeJar = cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        Path providedJar = cacheRoot.resolve("org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar");
        Path devJar = cacheRoot.resolve("com/example/devtools/1.0.0/devtools-1.0.0.jar");
        createJarWithEntry(springBootJar, "org/springframework/boot/SpringApplication.class");
        createJarWithEntry(loaderJar, "org/springframework/boot/loader/launch/WarLauncher.class");
        createJarWithEntry(runtimeJar, "com/example/runtime/RuntimeLib.class");
        createJarWithEntry(providedJar, "org/apache/catalina/startup/Tomcat.class");
        createJarWithEntry(devJar, "com/example/dev/DevTools.class");
        Files.writeString(projectDir.resolve("zolt.lock"), """
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
                id = "org.apache.tomcat.embed:tomcat-embed-core"
                version = "10.1.40"
                source = "maven-central"
                scope = "provided"
                direct = true
                jar = "org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar"
                dependencies = []

                [[package]]
                id = "com.example:devtools"
                version = "1.0.0"
                source = "maven-central"
                scope = "dev"
                direct = true
                jar = "com/example/devtools/1.0.0/devtools-1.0.0.jar"
                dependencies = []
                """);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);

        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT_WAR));

        PackageResult result = packageService.packageJar(projectDir, config, cacheRoot);

        assertEquals(PackageMode.SPRING_BOOT_WAR, result.mode());
        assertEquals(projectDir.resolve("target/demo-0.1.0.war"), result.jarPath());
        assertTrue(result.hasMainClass());
        String runtimeNestedName = nestedJarName("com.example", "runtime-lib", "1.0.0");
        String springBootNestedName =
                nestedJarName("org.springframework.boot", "spring-boot", "4.0.6");
        String loaderNestedName =
                nestedJarName("org.springframework.boot", "spring-boot-loader", "4.0.6");
        String providedNestedName =
                nestedJarName("org.apache.tomcat.embed", "tomcat-embed-core", "10.1.40");
        String devNestedName = nestedJarName("com.example", "devtools", "1.0.0");
        String evidence = Files.readString(result.evidenceManifestPath().orElseThrow());
        assertTrue(evidence.contains("\"archive\": \"target/demo-0.1.0.war\""));
        assertTrue(evidence.contains("\"coordinate\": \"org.apache.tomcat.embed:tomcat-embed-core:10.1.40\""));
        assertTrue(evidence.contains("\"scope\": \"provided\""));
        assertTrue(evidence.contains("\"disposition\": \"provided\""));
        assertTrue(evidence.contains("\"rule\": \"spring-boot-war-provided-lib\""));
        assertTrue(evidence.contains(
                "\"location\": \"WEB-INF/lib-provided/" + providedNestedName + "\""));
        assertTrue(evidence.contains("\"laneDisposition\": \"provided-container\""));
        assertTrue(evidence.contains("\"coordinate\": \"com.example:devtools:1.0.0\""));
        assertTrue(evidence.contains("\"rule\": \"dev-only-omitted\""));
        try (JarFile jar = new JarFile(result.jarPath().toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals(
                    "org.springframework.boot.loader.launch.WarLauncher",
                    attributes.getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("com.example.Main", attributes.getValue("Start-Class"));
            assertEquals("4.0.6", attributes.getValue("Spring-Boot-Version"));
            assertEquals("WEB-INF/classes/", attributes.getValue("Spring-Boot-Classes"));
            assertEquals("WEB-INF/lib/", attributes.getValue("Spring-Boot-Lib"));
            assertEquals("WEB-INF/lib-provided/", attributes.getValue("Spring-Boot-Lib-Provided"));
            assertNotNull(jar.getEntry("org/springframework/boot/loader/launch/WarLauncher.class"));
            assertNotNull(jar.getEntry("WEB-INF/classes/com/example/Main.class"));
            assertNotNull(jar.getEntry("WEB-INF/lib/" + runtimeNestedName));
            assertNotNull(jar.getEntry("WEB-INF/lib/" + springBootNestedName));
            assertNotNull(jar.getEntry("WEB-INF/lib-provided/" + providedNestedName));
            assertEquals(1, jar.stream()
                    .filter(entry -> entry.getName().equals("WEB-INF/lib/" + runtimeNestedName))
                    .count());
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + loaderNestedName)));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + devNestedName)));
        }
    }
}
