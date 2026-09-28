package sh.zolt.manifest;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Languages and platforms that require source-root admission checks.
 *
 * <p>Kotlin is admitted only for explicitly authored main roots. Kotlin test/integration roots,
 * Scala, and Android still fail at the parse boundary. The all-language recognizer also serves
 * migration drafting, which keeps Kotlin roots as review notes until automatic migration is ready.
 */
public enum SourceRootLanguage {
    KOTLIN(
            "Kotlin",
            "Kotlin is supported only for explicitly authored main roots in [build].sources during"
                    + " the preview. Kotlin test and integration roots and automatic migration are"
                    + " not supported yet."),
    SCALA(
            "Scala",
            "Scala is not supported in the public beta. Use Java source roots such as src/main/java,"
                    + " or keep Scala modules outside the Zolt beta scope."),
    ANDROID(
            "Android",
            "Android projects are not supported in the public beta. Use normal Java application source"
                    + " roots, or keep Android modules outside the Zolt beta scope.");

    private final String label;
    private final String remedy;

    SourceRootLanguage(String label, String remedy) {
        this.label = label;
        this.remedy = remedy;
    }

    /** A restricted language a source root names, including Kotlin for migration readiness checks. */
    public static Optional<SourceRootLanguage> unsupported(String root) {
        Objects.requireNonNull(root, "Source root must not be null.");
        return recognized(root, true);
    }

    /** The authored main root, admitting Kotlin while still rejecting Scala and Android. */
    public static ManifestRelativePath requireMainSupported(ManifestRelativePath root) {
        return requireSupported(root, false);
    }

    /** The authored source root, or an actionable failure naming the unsupported language. */
    public static ManifestRelativePath requireSupported(ManifestRelativePath root) {
        return requireSupported(root, true);
    }

    private static ManifestRelativePath requireSupported(
            ManifestRelativePath root,
            boolean rejectKotlin) {
        Objects.requireNonNull(root, "Source root must not be null.");
        recognized(root.value(), rejectKotlin).ifPresent(language -> {
            throw new IllegalArgumentException(language.rejection(root.value()));
        });
        return root;
    }

    private static Optional<SourceRootLanguage> recognized(String root, boolean includeKotlin) {
        String normalized = root.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (includeKotlin && (hasPathSegment(normalized, "kotlin") || normalized.endsWith(".kt"))) {
            return Optional.of(KOTLIN);
        }
        if (hasPathSegment(normalized, "scala") || normalized.endsWith(".scala")) {
            return Optional.of(SCALA);
        }
        if (hasPathSegment(normalized, "android")) {
            return Optional.of(ANDROID);
        }
        return Optional.empty();
    }

    public String label() {
        return label;
    }

    public String remedy() {
        return remedy;
    }

    public String rejection(String root) {
        return "Unsupported " + label + " source root `" + root + "`. " + remedy;
    }

    private static boolean hasPathSegment(String path, String segment) {
        return path.equals(segment)
                || path.startsWith(segment + "/")
                || path.endsWith("/" + segment)
                || path.contains("/" + segment + "/");
    }
}
