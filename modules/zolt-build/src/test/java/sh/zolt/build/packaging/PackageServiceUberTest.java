package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.buildSettingsWithMetadata;
import static sh.zolt.build.packaging.PackageServiceTestSupport.readEntry;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.PackageException;
import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceUberTest {
    private final PackageService packageService = new PackageService();

    @TempDir
    private Path projectDir;

    @Test
    void packagesApplicationAndRuntimeDependenciesIntoDeterministicUberJar() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeJar(cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"), Map.of(
                "com/example/runtime/RuntimeLib.class", "runtime",
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
                "META-INF/RUNTIME.SF", "signature",
                "module-info.class", "module"));
        writeJar(cacheRoot.resolve("jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"), Map.of(
                "jakarta/servlet/Servlet.class", "provided"));
        writeJar(cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar"), Map.of(
                "com/example/Processor.class", "processor"));
        writeUberLockfile(projectDir, cacheRoot);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        source(projectDir, "src/main/resources/config/app.properties", "name=demo\n");

        PackageResult result = packageService.packageJar(projectDir, uberConfig(), cacheRoot);

        assertEquals(projectDir.resolve("target/demo-0.1.0.jar"), result.jarPath());
        assertEquals(PackageMode.UBER, result.mode());
        assertTrue(result.hasMainClass());
        assertTrue(result.runtimeClasspathPath().isEmpty());
        try (JarFile jar = new JarFile(result.jarPath().toFile())) {
            assertNotNull(jar.getEntry("META-INF/MANIFEST.MF"));
            assertNotNull(jar.getEntry("com/example/Main.class"));
            assertNotNull(jar.getEntry("config/app.properties"));
            assertNotNull(jar.getEntry("com/example/runtime/RuntimeLib.class"));
            assertNull(jar.getEntry("jakarta/servlet/Servlet.class"));
            assertNull(jar.getEntry("com/example/Processor.class"));
            assertNull(jar.getEntry("META-INF/RUNTIME.SF"));
            assertNull(jar.getEntry("module-info.class"));
            assertEquals("name=demo\n", readEntry(jar, "config/app.properties"));
            Attributes attributes = jar.getManifest().getMainAttributes();
            assertEquals("com.example.Main", attributes.getValue(Attributes.Name.MAIN_CLASS));
        }
    }

    @Test
    void duplicateUberJarEntriesFailClearly() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeJar(cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"), Map.of(
                "config/app.properties", "dependency value"));
        writeUberLockfile(projectDir, cacheRoot);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        source(projectDir, "src/main/resources/config/app.properties", "application value\n");

        PackageException exception = assertThrows(
                PackageException.class,
                () -> packageService.packageJar(projectDir, uberConfig(), cacheRoot));

        assertTrue(exception.getMessage().contains("Duplicate uber jar entry `config/app.properties`"));
        assertTrue(exception.getMessage().contains("Move one dependency out of the runtime classpath"));
    }

    @Test
    void recordsUberMergeDecisionsInPackageEvidenceDeterministically() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeJar(cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"), Map.of(
                "META-INF/services/com.example.Plugin", "com.example.RuntimePlugin\n",
                "META-INF/io.netty.versions.properties", "netty-common.version=4.1.115.Final\n",
                "META-INF/LICENSE.txt", "license",
                "module-info.class", "module"));
        writeJar(cacheRoot.resolve("io/netty/netty-helper/4.1.115.Final/netty-helper-4.1.115.Final.jar"), Map.of(
                "META-INF/services/com.example.Plugin", "com.example.NettyPlugin\n",
                "META-INF/io.netty.versions.properties", "netty-buffer.version=4.1.115.Final\n",
                "META-INF/NOTICE", "notice",
                "META-INF/versions/9/module-info.class", "module"));
        writeUberMergeLockfile(projectDir, cacheRoot);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);

        PackageResult first = packageService.packageJar(projectDir, uberConfig(), cacheRoot);
        String firstEvidence = Files.readString(first.evidenceManifestPath().orElseThrow());
        PackageResult second = packageService.packageJar(projectDir, uberConfig(), cacheRoot);
        String secondEvidence = Files.readString(second.evidenceManifestPath().orElseThrow());

        assertEquals(firstEvidence, secondEvidence);
        assertTrue(firstEvidence.contains("\"uberMergeDecisions\": ["));
        assertTrue(firstEvidence.contains("\"kind\": \"service-descriptor\""));
        assertTrue(firstEvidence.contains("\"path\": \"META-INF/services/com.example.Plugin\""));
        assertTrue(firstEvidence.contains("\"sources\": [\"com.example:runtime-lib\", \"io.netty:netty-helper\"]"));
        assertTrue(firstEvidence.contains("\"kind\": \"netty-version-metadata\""));
        assertTrue(firstEvidence.contains("\"path\": \"META-INF/io.netty.versions.properties\""));
        assertTrue(firstEvidence.contains("\"kind\": \"relocated-metadata\""));
        assertTrue(firstEvidence.contains("\"path\": \"META-INF/LICENSE.txt\""));
        assertTrue(firstEvidence.contains("\"target\": \"META-INF/zolt-uber/com/example/runtime-lib/1.0.0/LICENSE.txt\""));
        assertTrue(firstEvidence.contains("\"path\": \"META-INF/NOTICE\""));
        assertTrue(firstEvidence.contains("\"target\": \"META-INF/zolt-uber/io/netty/netty-helper/4.1.115.Final/NOTICE\""));
        assertTrue(firstEvidence.contains("\"kind\": \"omitted-module-descriptor\""));
        assertTrue(firstEvidence.contains("\"path\": \"module-info.class\""));
        assertTrue(firstEvidence.contains("\"path\": \"META-INF/versions/9/module-info.class\""));
    }

    private static ProjectConfig uberConfig() {
        return config(Optional.of("com.example.Main"))
                .withBuildSettings(buildSettingsWithMetadata(new BuildMetadataSettings(false, false, true)))
                .withPackageSettings(new PackageSettings(PackageMode.UBER));
    }

    private static void writeUberLockfile(Path projectDir, Path cacheRoot) throws IOException {
        writeJarIfMissing(
                cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"));
        writeJarIfMissing(
                cacheRoot.resolve("jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"));
        writeJarIfMissing(
                cacheRoot.resolve("com/example/processor/1.0.0/processor-1.0.0.jar"));
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 1

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
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
    }

    private static void writeUberMergeLockfile(Path projectDir, Path cacheRoot) throws IOException {
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 1

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "io.netty:netty-helper"
                version = "4.1.115.Final"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "io/netty/netty-helper/4.1.115.Final/netty-helper-4.1.115.Final.jar"
                dependencies = []
                """);
    }

    private static void writeJar(Path jarPath, Map<String, String> entries) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private static void writeJarIfMissing(Path jarPath) throws IOException {
        if (Files.notExists(jarPath)) {
            writeJar(jarPath, Map.of());
        }
    }
}
