package sh.zolt.manifest;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The languages and platforms Zolt does not build, recognized from a source-root path.
 *
 * <p>Zolt compiles Java and runs Groovy test sources. A Kotlin, Scala, or Android root would be
 * silently ignored by the compiler pipeline, so §10.1 requires an authored one to fail actionably at
 * the parse boundary. The same recognizer serves migration drafting, which keeps such a root as a
 * review note instead of emitting a manifest that cannot parse.
 */
public enum SourceRootLanguage {
    KOTLIN(
            "Kotlin",
            "Kotlin is not supported in the public beta. Use Java source roots such as src/main/java,"
                    + " or keep Kotlin modules outside the Zolt beta scope."),
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

    /** The unsupported language a source root names, or empty when Zolt can build the root. */
    public static Optional<SourceRootLanguage> unsupported(String root) {
        Objects.requireNonNull(root, "Source root must not be null.");
        String normalized = root.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (hasPathSegment(normalized, "kotlin") || normalized.endsWith(".kt")) {
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

    /** The authored source root, or an actionable failure naming the unsupported language. */
    public static ManifestRelativePath requireSupported(ManifestRelativePath root) {
        Objects.requireNonNull(root, "Source root must not be null.");
        unsupported(root.value()).ifPresent(language -> {
            throw new IllegalArgumentException(language.rejection(root.value()));
        });
        return root;
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
