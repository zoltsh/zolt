package sh.zolt.init;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Language-specific starter source text emitted by {@link ProjectInitializer}. */
final class ProjectSourceTemplates {
    private static final Set<String> KOTLIN_HARD_KEYWORDS = Set.of(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this",
            "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while");

    private ProjectSourceTemplates() {
    }

    static String mainSource(
            String projectName, String group, ProjectInitLanguage language) {
        return switch (language) {
            case JAVA -> """
                package %s;

                public final class Main {
                    private Main() {
                    }

                    public static void main(String[] args) {
                        System.out.println(greeting());
                    }

                    static String greeting() {
                        return "Hello from %s!";
                    }
                }
                """.formatted(group, escapeJavaString(projectName));
            case KOTLIN -> """
                package %s

                object Main {
                    @JvmStatic
                    fun main(args: Array<String>) {
                        println(greeting())
                    }

                    internal fun greeting(): String = "Hello from %s!"
                }
                """.formatted(kotlinPackage(group), escapeKotlinString(projectName));
        };
    }

    static String testSource(
            String projectName, String group, ProjectInitLanguage language) {
        return switch (language) {
            case JAVA -> """
                package %s;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                import org.junit.jupiter.api.Test;

                final class MainTest {
                    @Test
                    void greets() {
                        assertEquals("Hello from %s!", Main.greeting());
                    }
                }
                """.formatted(group, escapeJavaString(projectName));
            case KOTLIN -> """
                package %s

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class MainTest {
                    @Test
                    fun greets() {
                        assertEquals("Hello from %s!", Main.greeting())
                    }
                }
                """.formatted(kotlinPackage(group), escapeKotlinString(projectName));
        };
    }

    private static String kotlinPackage(String group) {
        return Arrays.stream(group.split("\\."))
                .map(ProjectSourceTemplates::kotlinPackageSegment)
                .collect(Collectors.joining("."));
    }

    private static String kotlinPackageSegment(String segment) {
        return KOTLIN_HARD_KEYWORDS.contains(segment) || !isBareKotlinIdentifier(segment)
                ? "`" + segment + "`"
                : segment;
    }

    private static boolean isBareKotlinIdentifier(String value) {
        if (value.isEmpty() || !isAsciiLetterOrUnderscore(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isAsciiLetterOrUnderscore(character) && !Character.isDigit(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetterOrUnderscore(char character) {
        return character == '_'
                || (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z');
    }

    private static String escapeJavaString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String escapeKotlinString(String value) {
        return escapeJavaString(value).replace("$", "\\$");
    }
}
