package sh.zolt.build.generatedsource.ksp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KspJvmCommandBuilderTest {
    @Test
    void buildsDeterministicConservativeJvmCommand(@TempDir Path temporary) {
        Path root = temporary.toAbsolutePath().normalize();
        KspJvmCommandBuilder builder = new KspJvmCommandBuilder(":");

        List<String> command = builder.command(invocation(root));

        assertEquals(root.resolve("jdk/bin/java").toString(), command.getFirst());
        assertEquals("-Dksp.logging=warn", command.get(1));
        assertEquals(
                root.resolve("tools/ksp-aa.jar") + ":" + root.resolve("tools/ksp-api.jar"),
                value(command, "-cp"));
        assertEquals(KspJvmCommandBuilder.MAIN_CLASS, command.get(4));
        assertEquals("21", option(command, "jvm-target"));
        assertEquals("demo.main", option(command, "module-name"));
        assertEquals(
                root.resolve("src/main/kotlin") + ":" + root.resolve("generated/kotlin"),
                option(command, "source-roots"));
        assertEquals(root.resolve("src/main/java").toString(), option(command, "java-source-roots"));
        assertEquals(
                root.resolve("lib/api.jar") + ":" + root.resolve("lib/runtime.jar"),
                option(command, "libraries"));
        assertEquals(root.resolve("friend/classes").toString(), option(command, "friends"));
        assertEquals(root.resolve("jdk").toString(), option(command, "jdk-home"));
        assertEquals("2.2", option(command, "language-version"));
        assertEquals("2.1", option(command, "api-version"));
        assertEquals("no-compatibility", option(command, "jvm-default-mode"));
        assertEquals("false", option(command, "incremental"));
        assertEquals("true", option(command, "all-warnings-as-errors"));
        assertEquals("true", option(command, "map-annotation-arguments-in-java"));
        assertEquals("alpha=first:zeta=last", option(command, "processor-options"));
        assertEquals(
                root.resolve("processors/one.jar") + ":" + root.resolve("processors/two.jar"),
                command.getLast());
    }

    @Test
    void omitsOptionalArgumentsWithoutChangingRequiredOwnership(@TempDir Path temporary) {
        Path root = temporary.toAbsolutePath().normalize();
        KspJvmInvocation base = invocation(root);
        KspJvmInvocation minimal = new KspJvmInvocation(
                base.javaExecutable(),
                base.jdkHome(),
                base.engineClasspath(),
                base.processorClasspath(),
                base.kotlinSourceRoots(),
                List.of(),
                List.of(),
                List.of(),
                base.projectBaseDirectory(),
                base.outputBaseDirectory(),
                base.cachesDirectory(),
                base.classOutputDirectory(),
                base.kotlinOutputDirectory(),
                base.javaOutputDirectory(),
                base.resourceOutputDirectory(),
                base.jvmTarget(),
                base.moduleName(),
                base.languageVersion(),
                base.apiVersion(),
                "",
                false,
                false,
                Map.of());

        List<String> command = new KspJvmCommandBuilder(":").command(minimal);

        assertFalse(hasOption(command, "java-source-roots"));
        assertFalse(hasOption(command, "libraries"));
        assertFalse(hasOption(command, "friends"));
        assertFalse(hasOption(command, "jvm-default-mode"));
        assertFalse(hasOption(command, "all-warnings-as-errors"));
        assertFalse(hasOption(command, "processor-options"));
        assertEquals("false", option(command, "incremental"));
    }

    @Test
    void rejectsAmbiguousProcessorOptions(@TempDir Path temporary) {
        Path root = temporary.toAbsolutePath().normalize();
        KspJvmInvocation base = invocation(root);
        KspJvmInvocation ambiguous = new KspJvmInvocation(
                base.javaExecutable(),
                base.jdkHome(),
                base.engineClasspath(),
                base.processorClasspath(),
                base.kotlinSourceRoots(),
                base.javaSourceRoots(),
                base.libraries(),
                base.friends(),
                base.projectBaseDirectory(),
                base.outputBaseDirectory(),
                base.cachesDirectory(),
                base.classOutputDirectory(),
                base.kotlinOutputDirectory(),
                base.javaOutputDirectory(),
                base.resourceOutputDirectory(),
                base.jvmTarget(),
                base.moduleName(),
                base.languageVersion(),
                base.apiVersion(),
                base.jvmDefaultMode(),
                base.warningsAsErrors(),
                base.mapAnnotationArgumentsInJava(),
                Map.of("bad=name", "value"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new KspJvmCommandBuilder(":").command(ambiguous));
    }

    private static KspJvmInvocation invocation(Path root) {
        return new KspJvmInvocation(
                root.resolve("jdk/bin/java"),
                root.resolve("jdk"),
                List.of(root.resolve("tools/ksp-aa.jar"), root.resolve("tools/ksp-api.jar")),
                List.of(root.resolve("processors/one.jar"), root.resolve("processors/two.jar")),
                List.of(root.resolve("src/main/kotlin"), root.resolve("generated/kotlin")),
                List.of(root.resolve("src/main/java")),
                List.of(root.resolve("lib/api.jar"), root.resolve("lib/runtime.jar")),
                List.of(root.resolve("friend/classes")),
                root,
                root.resolve("target/generated/ksp/main"),
                root.resolve("target/.zolt/ksp/main/cache"),
                root.resolve("target/generated/ksp/main/classes"),
                root.resolve("target/generated/ksp/main/kotlin"),
                root.resolve("target/generated/ksp/main/java"),
                root.resolve("target/generated/ksp/main/resources"),
                "21",
                "demo.main",
                "2.2",
                "2.1",
                "no-compatibility",
                true,
                true,
                Map.of("zeta", "last", "alpha", "first"));
    }

    private static String value(List<String> command, String argument) {
        return command.get(command.indexOf(argument) + 1);
    }

    private static String option(List<String> command, String name) {
        String prefix = "-" + name + "=";
        return command.stream()
                .filter(argument -> argument.startsWith(prefix))
                .findFirst()
                .orElseThrow()
                .substring(prefix.length());
    }

    private static boolean hasOption(List<String> command, String name) {
        String prefix = "-" + name + "=";
        return command.stream().anyMatch(argument -> argument.startsWith(prefix));
    }
}
