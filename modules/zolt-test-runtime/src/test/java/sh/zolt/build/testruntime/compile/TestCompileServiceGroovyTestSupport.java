package sh.zolt.build.testruntime.compile;

import sh.zolt.classpath.Classpath;
import sh.zolt.build.compile.JavacRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

abstract class TestCompileServiceGroovyTestSupport {
    protected static void createFakeGroovyCompilerJar(
            Path projectDir,
            Path jar,
            String version) throws IOException {
        Path compilerSource = projectDir.resolve(
                "fake-groovy-compiler-src/org/codehaus/groovy/tools/FileSystemCompiler.java");
        Files.createDirectories(compilerSource.getParent());
        Files.writeString(compilerSource, """
                package org.codehaus.groovy.tools;

                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.ArrayList;
                import java.util.List;

                public final class FileSystemCompiler {
                    public static void main(String[] args) throws Exception {
                        Path output = null;
                        List<String> sources = new ArrayList<>();
                        for (int index = 0; index < args.length; index++) {
                            if ("-d".equals(args[index])) {
                                output = Path.of(args[++index]);
                            } else if ("-classpath".equals(args[index])) {
                                index++;
                            } else if (args[index].endsWith(".groovy")) {
                                sources.add(args[index]);
                            }
                        }
                        if (output == null) {
                            throw new IllegalArgumentException("-d is required");
                        }
                        for (String source : sources) {
                            String normalized = source.replace('\\\\', '/');
                            String marker = "/src/test/groovy/";
                            int markerIndex = normalized.indexOf(marker);
                            String relative = markerIndex >= 0
                                    ? normalized.substring(markerIndex + marker.length())
                                    : Path.of(source).getFileName().toString();
                            relative = relative.substring(0, relative.length() - ".groovy".length()) + ".class";
                            Path classFile = output.resolve(relative);
                            Files.createDirectories(classFile.getParent());
                            Files.write(classFile, new byte[] {0, 0});
                        }
                        System.out.println("fake groovy compiler %s");
                    }
                }
                """.formatted(version));
        Path classes = projectDir.resolve("fake-groovy-compiler-classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(compilerSource),
                new Classpath(List.of()),
                classes);

        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            JarEntry entry = new JarEntry("org/codehaus/groovy/tools/FileSystemCompiler.class");
            output.putNextEntry(entry);
            output.write(Files.readAllBytes(classes.resolve("org/codehaus/groovy/tools/FileSystemCompiler.class")));
            output.closeEntry();
        }
    }

    protected static Path currentJavac() {
        return Path.of(System.getProperty("java.home")).resolve("bin").resolve(executable("javac"));
    }

    protected static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    protected static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
