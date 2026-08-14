package sh.zolt.cli.packaging;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.createJarWithEntry;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.nestedJarName;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.writeMainSource;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.writeProjectConfig;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.writeSpringBootWarProvidedTomcatLockfile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageSpringBootWarCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void packageCommandBuildsSpringBootWarWithProvidedTomcatLane() throws IOException {
        Path projectDir = tempDir.resolve("spring-boot-war-provided-tomcat");
        Path cacheRoot = tempDir.resolve("cache");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                """

                [package]
                mode = "spring-boot-war"

                [provided.dependencies]
                "org.apache.tomcat.embed:tomcat-embed-core" = "10.1.40"
                """,
                StandardOpenOption.APPEND);
        writeMainSource(projectDir, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        createJarWithEntry(
                cacheRoot.resolve("org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar"),
                "org/springframework/boot/SpringApplication.class");
        createJarWithEntry(
                cacheRoot.resolve(
                        "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar"),
                "org/springframework/boot/loader/launch/WarLauncher.class");
        createJarWithEntry(
                cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"),
                "com/example/runtime/RuntimeLib.class");
        createJarWithEntry(
                cacheRoot.resolve(
                        "org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar"),
                "org/apache/catalina/startup/Tomcat.class");
        createJarWithEntry(
                cacheRoot.resolve("com/example/devtools/1.0.0/devtools-1.0.0.jar"),
                "com/example/dev/DevTools.class");
        createJarWithEntry(
                cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar"),
                "com/example/processor/Processor.class");
        writeSpringBootWarProvidedTomcatLockfile(projectDir, cacheRoot);

        CommandResult result = execute(
                "package",
                "--mode", "spring-boot-war",
                "--cwd", projectDir.toString(),
                "--cache-root", cacheRoot.toString());

        Path warPath = projectDir.resolve("target/demo-0.1.0.war");
        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
        assertTrue(result.stdout().contains("Packaged"));
        assertTrue(result.stdout().contains("spring-boot-war"));
        assertTrue(result.stdout().contains("→ wrote " + warPath));
        assertTrue(result.stdout().contains("→ wrote " + warPath + ".zolt-package.json"));
        assertTrue(Files.exists(projectDir.resolve("target/demo-0.1.0.war.zolt-package.json")));
        assertFalse(result.stdout().contains("CONTAINER_DEPENDENCY_PACKAGED"));
        String evidence = Files.readString(projectDir.resolve("target/demo-0.1.0.war.zolt-package.json"));
        assertTrue(evidence.contains("\"rule\": \"spring-boot-war-provided-coordinate-override\""));
        assertTrue(evidence.contains("\"rule\": \"spring-boot-war-provided-lib\""));
        String runtimeNestedName =
                nestedJarName("com.example", "runtime-lib", "1.0.0");
        String providedNestedName =
                nestedJarName(
                        "org.apache.tomcat.embed",
                        "tomcat-embed-core",
                        "10.1.40");
        String devNestedName =
                nestedJarName("com.example", "devtools", "1.0.0");
        String processorNestedName =
                nestedJarName("com.example", "processor", "1.0.0");
        try (JarFile jar = new JarFile(warPath.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals(
                    "org.springframework.boot.loader.launch.WarLauncher",
                    attributes.getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("com.example.Main", attributes.getValue("Start-Class"));
            assertEquals("WEB-INF/lib-provided/", attributes.getValue("Spring-Boot-Lib-Provided"));
            assertNotNull(jar.getEntry("WEB-INF/lib/" + runtimeNestedName));
            assertNotNull(jar.getEntry("WEB-INF/lib-provided/" + providedNestedName));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + providedNestedName)));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + devNestedName)));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + processorNestedName)));
        }
    }
}
