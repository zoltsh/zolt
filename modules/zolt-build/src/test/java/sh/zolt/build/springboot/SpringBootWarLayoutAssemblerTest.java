package sh.zolt.build.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageRuntimeJar;
import sh.zolt.build.packaging.PackageRuntimeJars;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.PackageMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpringBootWarLayoutAssemblerTest {
    private final SpringBootWarLayoutAssembler assembler = new SpringBootWarLayoutAssembler();

    @TempDir
    private Path projectDir;

    @Test
    void assemblesExecutableWarLayoutWithRuntimeAndProvidedJars() throws IOException {
        Path outputDirectory = projectDir.resolve("target/classes");
        Path mainClass = outputDirectory.resolve("com/example/Main.class");
        Files.createDirectories(mainClass.getParent());
        Files.write(mainClass, new byte[] {1});
        Files.writeString(outputDirectory.resolve("application.properties"), "name=demo\n");
        Files.writeString(outputDirectory.resolve(".zolt-incremental-main.state"), "state\n");
        Path springBootJar = projectDir.resolve("cache/org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar");
        Path loaderJar = projectDir.resolve(
                "cache/org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar");
        Path runtimeJar = projectDir.resolve("cache/com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar");
        Path providedJar = projectDir.resolve(
                "cache/org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar");
        createJarWithEntry(springBootJar, "org/springframework/boot/SpringApplication.class");
        createJarWithEntry(loaderJar, "org/springframework/boot/loader/launch/WarLauncher.class");
        createJarWithEntry(runtimeJar, "com/example/runtime/RuntimeLib.class");
        createJarWithEntry(providedJar, "org/apache/catalina/startup/Tomcat.class");
        Path warPath = projectDir.resolve("target/demo-0.1.0.war");

        PackageRuntimeJar springBootInput =
                new PackageRuntimeJar(SpringBootLoaderSupport.SPRING_BOOT_PACKAGE, "4.0.6", springBootJar);
        PackageRuntimeJar loaderInput =
                new PackageRuntimeJar(SpringBootLoaderSupport.SPRING_BOOT_LOADER_PACKAGE, "4.0.6", loaderJar);
        PackageRuntimeJar runtimeInput =
                new PackageRuntimeJar(new PackageId("com.example", "runtime-lib"), "1.0.0", runtimeJar);
        PackageRuntimeJar providedInput = new PackageRuntimeJar(
                new PackageId("org.apache.tomcat.embed", "tomcat-embed-core"),
                "10.1.40",
                providedJar);
        PackageResult result = assembler.assemble(
                "com.example.Main",
                new BuildResult(Optional.empty(), 1, 1, outputDirectory, ""),
                outputDirectory,
                warPath,
                List.of(springBootInput, loaderInput, runtimeInput),
                List.of(providedInput));

        assertEquals(PackageMode.SPRING_BOOT_WAR, result.mode());
        assertEquals(warPath, result.jarPath());
        assertEquals(Optional.empty(), result.runtimeClasspathPath());
        assertEquals(2, result.entryCount());
        assertTrue(result.hasMainClass());
        try (JarFile jar = new JarFile(warPath.toFile())) {
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("1.0", attributes.getValue(Attributes.Name.MANIFEST_VERSION));
            assertEquals(
                    "org.springframework.boot.loader.launch.WarLauncher",
                    attributes.getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("com.example.Main", attributes.getValue("Start-Class"));
            assertEquals("4.0.6", attributes.getValue("Spring-Boot-Version"));
            assertEquals("WEB-INF/classes/", attributes.getValue("Spring-Boot-Classes"));
            assertEquals("WEB-INF/lib/", attributes.getValue("Spring-Boot-Lib"));
            assertEquals("WEB-INF/lib-provided/", attributes.getValue("Spring-Boot-Lib-Provided"));
            assertNotNull(jar.getEntry("WEB-INF/"));
            assertNotNull(jar.getEntry("WEB-INF/classes/"));
            assertNotNull(jar.getEntry("WEB-INF/lib/"));
            assertNotNull(jar.getEntry("WEB-INF/lib-provided/"));
            assertNotNull(jar.getEntry("WEB-INF/classes/com/example/Main.class"));
            assertNotNull(jar.getEntry("WEB-INF/classes/application.properties"));
            assertNotNull(jar.getEntry("org/springframework/boot/loader/launch/WarLauncher.class"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(".zolt-incremental-main.state")));
            assertNotNull(jar.getEntry(
                    "WEB-INF/lib/" + PackageRuntimeJars.nestedJarName(springBootInput)));
            JarEntry runtimeEntry =
                    jar.getJarEntry("WEB-INF/lib/" + PackageRuntimeJars.nestedJarName(runtimeInput));
            assertNotNull(runtimeEntry);
            assertEquals(JarEntry.STORED, runtimeEntry.getMethod());
            JarEntry providedEntry = jar.getJarEntry(
                    "WEB-INF/lib-provided/" + PackageRuntimeJars.nestedJarName(providedInput));
            assertNotNull(providedEntry);
            assertEquals(JarEntry.STORED, providedEntry.getMethod());
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals(
                    "WEB-INF/lib/" + PackageRuntimeJars.nestedJarName(loaderInput))));
        }
    }

    private static void createJarWithEntry(Path jarPath, String entryName) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            JarEntry entry = new JarEntry(entryName);
            jar.putNextEntry(entry);
            jar.write(new byte[] {1});
            jar.closeEntry();
        }
    }
}
