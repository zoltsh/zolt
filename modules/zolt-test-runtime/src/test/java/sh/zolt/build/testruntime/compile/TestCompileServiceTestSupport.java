package sh.zolt.build.testruntime.compile;

import static sh.zolt.build.TestContentAddressedLockSupport.write;

import sh.zolt.classpath.Classpath;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class TestCompileServiceTestSupport {
    private TestCompileServiceTestSupport() {
    }

    static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", currentJavaMajorVersion(), Optional.of("com.example.Main")),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    static Path source(Path projectDir, String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    static void writeLockfile(Path projectDir, String content) throws IOException {
        write(projectDir, content);
    }

    static void createHelperJar(Path projectDir, Path jar) throws IOException {
        Path helperSource = projectDir.resolve("helper-src/com/example/helper/Helper.java");
        Files.createDirectories(helperSource.getParent());
        Files.writeString(helperSource, """
                package com.example.helper;

                public final class Helper {
                    public static String message() {
                        return "helper";
                    }
                }
                """);
        Path helperClasses = projectDir.resolve("helper-classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(helperSource),
                new Classpath(List.of()),
                helperClasses);

        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("com/example/helper/Helper.class");
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(helperClasses.resolve("com/example/helper/Helper.class")));
            output.closeEntry();
        }
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
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
