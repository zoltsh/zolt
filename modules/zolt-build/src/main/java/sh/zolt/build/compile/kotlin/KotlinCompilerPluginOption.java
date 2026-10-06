package sh.zolt.build.compile.kotlin;

import java.util.regex.Pattern;

/** One Zolt-owned option for a checksum-verified Kotlin compiler plugin. */
public record KotlinCompilerPluginOption(
        String pluginId,
        String name,
        String value) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

    public KotlinCompilerPluginOption {
        pluginId = identifier(pluginId, "plugin ID");
        name = identifier(name, "option name");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Kotlin compiler plugin option value is required.");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "Kotlin compiler plugin option value must stay on one line.");
        }
    }

    public String argument() {
        return "plugin:" + pluginId + ":" + name + "=" + value;
    }

    private static String identifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Kotlin compiler plugin " + label + " is invalid: `" + value + "`.");
        }
        return value;
    }
}
