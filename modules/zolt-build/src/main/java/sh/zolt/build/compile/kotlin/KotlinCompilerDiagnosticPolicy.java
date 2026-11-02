package sh.zolt.build.compile.kotlin;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;

/** Fail-closed handling for compiler diagnostics that contradict a successful process exit. */
public final class KotlinCompilerDiagnosticPolicy {
    private static final Pattern UNSUPPORTED_OPTION = Pattern.compile(
            "(?im)^\\s*(?:w:\\s*)?warning:\\s+(?:flag|option)\\b.*"
                    + "\\bnot supported by this version of (?:the )?compiler\\b.*$");

    private KotlinCompilerDiagnosticPolicy() {
    }

    public static void requireNoUnsupportedOption(
            String output,
            KotlinCompilationScope scope) {
        String diagnostics = output == null ? "" : output;
        Matcher matcher = UNSUPPORTED_OPTION.matcher(diagnostics);
        if (!matcher.find()) {
            return;
        }
        KotlinCompilationScope compilationScope = Objects.requireNonNull(
                scope,
                "Kotlin compilation scope is required.");
        throw new KotlinCompileException(
                "Kotlin " + compilationScope.label()
                        + " compilation failed because the selected compiler reported an unsupported "
                        + "requested option while returning success. Select a supported stable Kotlin "
                        + "2.2.x toolchain or remove the option, run `zolt resolve`, and retry.\n"
                        + diagnostics.stripTrailing());
    }
}
