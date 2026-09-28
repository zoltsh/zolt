package sh.zolt.cli.nativeimage;

import sh.zolt.cli.CliTestSupport;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

final class CliNativeReflectionConfigTest {
    private static final Pattern REFLECTION_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final String COMMAND_PACKAGE = "sh.zolt.cli.command.";

    @Test
    void registeredPicocliTypesHaveNativeReflectionMetadata() throws IOException {
        Set<String> requiredTypes = requiredReflectionTypes(CliTestSupport.newCommandLine());
        Set<String> configuredTypes = reflectionConfigNames(reflectConfigPath());

        List<String> missingTypes = requiredTypes.stream()
                .filter(type -> !configuredTypes.contains(type))
                .sorted()
                .toList();
        List<String> staleCommandTypes = configuredTypes.stream()
                .filter(type -> type.startsWith(COMMAND_PACKAGE))
                .filter(type -> !requiredTypes.contains(type))
                .sorted()
                .toList();

        assertTrue(
                missingTypes.isEmpty() && staleCommandTypes.isEmpty(),
                () -> nativeReflectionDriftMessage(missingTypes, staleCommandTypes));
    }

    @Test
    void reflectionConfigParserReadsConfiguredTypeNames(@TempDir Path tempDir) throws IOException {
        Path reflectConfig = tempDir.resolve("reflect-config.json");
        Files.writeString(
                reflectConfig,
                """
                [
                  { "name": "com.example.First", "allDeclaredFields": true },
                  { "name" : "com.example.Second" }
                ]
                """);

        assertEquals(
                Set.of("com.example.First", "com.example.Second"),
                reflectionConfigNames(reflectConfig));
    }

    @Test
    void requiredTypeScannerIncludesCommandsMixinsOptionEnumsAndConverters() {
        Set<String> requiredTypes = requiredReflectionTypes(new CommandLine(new FixtureCommand()));

        assertTrue(requiredTypes.contains(FixtureCommand.class.getName()));
        assertTrue(requiredTypes.contains(FixtureCommand.Format.class.getName()));
        assertTrue(requiredTypes.contains(FixtureCommand.NestedCommand.class.getName()));
        assertTrue(requiredTypes.contains(FixtureMixin.class.getName()));
        assertTrue(requiredTypes.contains(FixtureMode.class.getName()));
        assertTrue(requiredTypes.contains(FixtureModeConverter.class.getName()));
    }

    private static Set<String> requiredReflectionTypes(CommandLine root) {
        Set<String> types = new TreeSet<>();
        types.add("picocli.CommandLine$AutoHelpMixin");

        ArrayDeque<CommandLine> commandLines = new ArrayDeque<>();
        commandLines.add(root);
        while (!commandLines.isEmpty()) {
            CommandLine commandLine = commandLines.removeFirst();
            Object userObject = commandLine.getCommandSpec().userObject();
            if (userObject instanceof Class<?> commandClass) {
                addAnnotatedType(commandClass, types);
            } else if (userObject != null) {
                addAnnotatedType(userObject.getClass(), types);
            }
            commandLine.getSubcommands().values().forEach(commandLines::addLast);
        }

        return types;
    }

    private static void addAnnotatedType(Class<?> type, Set<String> types) {
        if (!types.add(type.getName())) {
            return;
        }
        Class<?> current = type;
        while (current != null && current != Object.class) {
            if (current != type && declaresPicocliFields(current)) {
                types.add(current.getName());
            }
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(Mixin.class)) {
                    addAnnotatedType(field.getType(), types);
                }
                Option option = field.getAnnotation(Option.class);
                Parameters parameters = field.getAnnotation(Parameters.class);
                if (option != null || parameters != null) {
                    addOptionValueType(field.getType(), types);
                }
                if (option != null) {
                    addConverters(option.converter(), types);
                }
                if (parameters != null) {
                    addConverters(parameters.converter(), types);
                }
            }
            current = current.getSuperclass();
        }
    }

    private static boolean declaresPicocliFields(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Mixin.class)
                    || field.isAnnotationPresent(Option.class)
                    || field.isAnnotationPresent(Parameters.class)) {
                return true;
            }
        }
        return false;
    }

    private static void addOptionValueType(Class<?> type, Set<String> types) {
        if (type.isEnum()) {
            types.add(type.getName());
        }
    }

    private static void addConverters(Class<?>[] converters, Set<String> types) {
        for (Class<?> converter : converters) {
            types.add(converter.getName());
        }
    }

    private static Set<String> reflectionConfigNames(Path path) throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher matcher = REFLECTION_NAME.matcher(Files.readString(path));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Path reflectConfigPath() {
        Path workspacePath = Path.of("apps/zolt/src/main/resources/META-INF/native-image/sh.zolt/zolt/reflect-config.json");
        if (Files.exists(workspacePath)) {
            return workspacePath;
        }
        return Path.of("src/main/resources/META-INF/native-image/sh.zolt/zolt/reflect-config.json");
    }

    private static String nativeReflectionDriftMessage(List<String> missingTypes, List<String> staleCommandTypes) {
        List<String> lines = new ArrayList<>();
        lines.add("Native reflection metadata drift in " + reflectConfigPath() + ".");
        if (!missingTypes.isEmpty()) {
            lines.add("Missing registered Picocli type(s):");
            missingTypes.forEach(type -> lines.add("- " + type));
        }
        if (!staleCommandTypes.isEmpty()) {
            lines.add("Stale command reflection metadata:");
            staleCommandTypes.forEach(type -> lines.add("- " + type));
        }
        lines.add("Update reflect-config.json when commands, option enums or converters, or Picocli mixins change.");
        return String.join(System.lineSeparator(), lines);
    }

    @Command(name = "fixture", subcommands = FixtureCommand.NestedCommand.class)
    static final class FixtureCommand implements Runnable {
        enum Format {
            TEXT,
            JSON
        }

        @Option(names = "--format")
        private Format format = Format.TEXT;

        @Mixin
        private FixtureMixin mixin = new FixtureMixin();

        @Override
        public void run() {
        }

        @Command(name = "nested")
        static final class NestedCommand implements Runnable {
            @Override
            public void run() {
            }
        }
    }

    static final class FixtureMixin {
        @Option(names = "--mode", converter = FixtureModeConverter.class)
        private FixtureMode mode = FixtureMode.FAST;
    }

    enum FixtureMode {
        FAST,
        SLOW
    }

    static final class FixtureModeConverter implements CommandLine.ITypeConverter<FixtureMode> {
        @Override
        public FixtureMode convert(String value) {
            return FixtureMode.valueOf(value);
        }
    }
}
