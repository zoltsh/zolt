package sh.zolt.build.compile;

import sh.zolt.home.UserGlobalDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * What makes two brokers interchangeable: the JDK that will compile, the worker artifact the
 * children run, the JVM flags they are started with, and the transport version.
 *
 * <p>The identity is the rendezvous-file name, so a changed JDK, a rebuilt worker jar, different
 * flags, or a protocol bump route to a different broker instead of silently reusing children that
 * were built for something else. Superseded brokers keep no clients and retire on their idle timeout.
 */
record JavacBrokerIdentity(Path javac, Path workerJar, List<String> jvmArguments) {
    private static final String MAIN_CLASS = "sh.zolt.javac.JavacWorkerMain";

    JavacBrokerIdentity {
        javac = javac.toAbsolutePath().normalize();
        workerJar = workerJar.toAbsolutePath().normalize();
        jvmArguments = List.copyOf(jvmArguments);
    }

    static JavacBrokerIdentity of(Path javac, Path workerJar) {
        return new JavacBrokerIdentity(javac, workerJar, configuredJvmArguments());
    }

    static List<String> configuredJvmArguments() {
        String configured = System.getProperty("zolt.javac.worker.jvmArgs", "");
        if (configured.isBlank()) {
            return List.of();
        }
        return List.of(configured.trim().split("\\s+"));
    }

    Path statePath(Path runtimeDirectory) throws IOException {
        String identity = javac
                + "\n" + workerJar
                + "\n" + Files.size(workerJar)
                + "\n" + Files.getLastModifiedTime(workerJar).toMillis()
                + "\n" + String.join(" ", jvmArguments)
                + "\n" + JavacBrokerWire.VERSION;
        return runtimeDirectory.resolve("broker-" + sha256(identity).substring(0, 24) + ".state");
    }

    static Path runtimeDirectory() {
        String configured = System.getProperty("zolt.javac.worker.runtimeDirectory", "");
        if (!configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return UserGlobalDirectory.runtime("javac");
    }

    List<String> startCommand(Path statePath) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(jvmArguments);
        command.add("-classpath");
        command.add(workerJar.toString());
        command.add(MAIN_CLASS);
        command.add("--broker");
        command.add(statePath.toString());
        for (String argument : jvmArguments) {
            command.add("--worker-jvm-arg");
            command.add(argument);
        }
        return List.copyOf(command);
    }

    private Path javaExecutable() {
        String fileName = javac.getFileName().toString();
        String name = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".exe") ? "java.exe" : "java";
        return javac.resolveSibling(name);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
