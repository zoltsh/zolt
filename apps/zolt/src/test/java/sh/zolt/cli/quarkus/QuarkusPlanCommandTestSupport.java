package sh.zolt.cli.quarkus;

import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class QuarkusPlanCommandTestSupport {
    private QuarkusPlanCommandTestSupport() {}

    static void writeProjectConfig(Path projectDir) throws IOException {
        writeProjectConfig(projectDir, "target");
    }

    static void writeProjectConfig(Path projectDir, String outputRoot) throws IOException {
        Files.createDirectories(projectDir);
        String output = outputRoot + "/classes";
        String testOutput = outputRoot + "/test-classes";
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + """
                main = "com.example.Main"

                [repositories]
                test = "https://repo.maven.apache.org/maven2"

                [dependencies]

                [test.dependencies]

                [build]
                outputRoot = "%s"
                source = "src/main/java"
                test = "src/test/java"
                output = "%s"
                testOutput = "%s"
                """.formatted(outputRoot, output, testOutput));
    }

    static void enableQuarkus(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), Files.readString(projectDir.resolve("zolt.toml")) + """

                [framework.quarkus]
                enabled = true
                package = "fast-jar"
                """);
    }

    static void createJarWithTextEntry(Path jar, String entryName, String text) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    static String quarkusInputFingerprint(String output) {
        return output.lines()
                .filter(line -> line.startsWith("Input fingerprint: "))
                .findFirst()
                .orElseThrow()
                .substring("Input fingerprint: ".length());
    }

    static void writeQuarkusPlanLockfile(Path projectDir, Path cacheRoot) throws IOException {
        write(projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 1

                [[package]]
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar"
                dependencies = []

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                jar = "io/quarkus/quarkus-rest-deployment/3.33.0/quarkus-rest-deployment-3.33.0.jar"
                dependencies = []
                """);
    }

    static void writeQuarkusAugmentationMetadata(Path projectDir, String inputFingerprint) throws IOException {
        writeQuarkusAugmentationMetadata(projectDir, "target", inputFingerprint);
    }

    static void writeQuarkusAugmentationMetadata(
            Path projectDir,
            String outputRoot,
            String inputFingerprint) throws IOException {
        Path metadata = projectDir.resolve(outputRoot).resolve("quarkus/zolt-augmentation.properties");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, """
                version=1
                inputFingerprint=%s
                """.formatted(inputFingerprint));
    }
}
