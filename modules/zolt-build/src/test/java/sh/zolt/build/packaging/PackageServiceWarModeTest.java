package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.createJarWithEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.nestedJarName;
import static sh.zolt.build.packaging.PackageServiceTestSupport.readEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.resourceFilteringSettings;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

final class PackageServiceWarModeTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void packagesWarLayoutWithRuntimeDependenciesOnly() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        Path runtimeJar = cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        Path providedJar = cacheRoot.resolve("jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar");
        Path devJar = cacheRoot.resolve("com/example/devtools/1.0.0/devtools-1.0.0.jar");
        createJarWithEntry(runtimeJar, "com/example/runtime/RuntimeLib.class");
        createJarWithEntry(providedJar, "jakarta/servlet/Servlet.class");
        createJarWithEntry(devJar, "com/example/dev/DevTools.class");
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                lane = "provided"
                resolvedScope = "provided"

                [[dependencyRoot]]
                member = "."
                id = "com.example:devtools"
                version = "1.0.0"
                lane = "dev"
                resolvedScope = "dev"

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                source = "maven-central"
                scope = "provided"
                direct = true
                jar = "jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"
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
        source(projectDir, "src/main/resources/application.properties", "name=@projectName@\n");
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withPackageSettings(new PackageSettings(PackageMode.WAR));
        config = config.withBuildSettings(config.build().withResourceFiltering(resourceFilteringSettings()));

        PackageResult result = packageService.packageJar(projectDir, config, cacheRoot);

        assertEquals(PackageMode.WAR, result.mode());
        assertEquals(projectDir.resolve("target/demo-0.1.0.war"), result.jarPath());
        assertEquals(Optional.empty(), result.runtimeClasspathPath());
        assertFalse(result.hasMainClass());
        String runtimeNestedName = nestedJarName("com.example", "runtime-lib", "1.0.0");
        String providedNestedName = nestedJarName("jakarta.servlet", "jakarta.servlet-api", "6.1.0");
        String devNestedName = nestedJarName("com.example", "devtools", "1.0.0");
        try (JarFile jar = new JarFile(result.jarPath().toFile())) {
            assertNotNull(jar.getEntry("META-INF/MANIFEST.MF"));
            assertFalse(jar.getManifest().getMainAttributes().containsKey(Attributes.Name.MAIN_CLASS));
            assertNotNull(jar.getEntry("WEB-INF/"));
            assertNotNull(jar.getEntry("WEB-INF/classes/"));
            assertNotNull(jar.getEntry("WEB-INF/lib/"));
            assertNotNull(jar.getEntry("WEB-INF/classes/com/example/Main.class"));
            assertEquals("name=demo\n", readEntry(jar, "WEB-INF/classes/application.properties"));
            assertNotNull(jar.getEntry("WEB-INF/lib/" + runtimeNestedName));
            assertEquals(JarEntry.STORED, jar.getEntry("WEB-INF/lib/" + runtimeNestedName).getMethod());
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + providedNestedName)));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + devNestedName)));
        }
    }
}
