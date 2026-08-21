package sh.zolt.cli.packaging;

import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.dependency.PackageId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class PackageSpringBootCommandTestSupport {
    private PackageSpringBootCommandTestSupport() {}

    static void writeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    static void writeMainSource(Path projectDir, String content) throws IOException {
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    static void createJarWithEntry(Path jar, String entryName) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write("\0".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    static String nestedJarName(
            String groupId,
            String artifactId,
            String version) {
        return NestedArtifactIdentity.external(
                        new PackageId(groupId, artifactId),
                        version)
                .nestedJarName();
    }

    static void writeSpringBootWarProvidedTomcatLockfile(Path projectDir, Path cacheRoot) throws IOException {
        write(projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.apache.tomcat.embed:tomcat-embed-core"
                version = "10.1.40"
                lane = "provided"
                resolvedScope = "provided"

                [[dependencyRoot]]
                member = "."
                id = "com.example:devtools"
                version = "1.0.0"
                lane = "dev"
                resolvedScope = "dev"

                [[dependencyRoot]]
                member = "."
                id = "com.example:processor"
                version = "1.0.0"
                lane = "processor"
                resolvedScope = "processor"

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
                id = "org.apache.tomcat.embed:tomcat-embed-core"
                version = "10.1.40"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "org/apache/tomcat/embed/tomcat-embed-core/10.1.40/tomcat-embed-core-10.1.40.jar"
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

    static void writeSpringBootLockfile(Path projectDir, Path cacheRoot) throws IOException {
        createJarWithEntry(
                cacheRoot.resolve("org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar"),
                "org/springframework/boot/SpringApplication.class");
        createJarWithEntry(
                cacheRoot.resolve(
                        "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar"),
                "org/springframework/boot/loader/launch/JarLauncher.class");
        createJarWithEntry(
                cacheRoot.resolve("com/example/runtime-lib/1.0.0/runtime-lib-1.0.0.jar"),
                "com/example/runtime/RuntimeLib.class");
        write(projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

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
                """);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
