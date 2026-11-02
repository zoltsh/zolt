package sh.zolt.ide;

import sh.zolt.manifest.SourceRootLanguage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class IdeSourceRootLanguages {
    private IdeSourceRootLanguages() {
    }

    static List<String> detect(String configuredRoot, Path sourceRoot) {
        boolean containsJava = containsSource(sourceRoot, ".java");
        boolean containsKotlin = containsSource(sourceRoot, ".kt");
        boolean configuredKotlin = SourceRootLanguage.unsupported(configuredRoot).orElse(null)
                == SourceRootLanguage.KOTLIN;
        if (containsJava && (containsKotlin || configuredKotlin)) {
            return List.of("java", "kotlin");
        }
        if (containsKotlin || configuredKotlin) {
            return List.of("kotlin");
        }
        return List.of("java");
    }

    static boolean includesGroovy(String configuredRoot, Path sourceRoot) {
        return hasGroovyPathSegment(configuredRoot) || containsSource(sourceRoot, ".groovy");
    }

    private static boolean hasGroovyPathSegment(String configuredRoot) {
        String normalized = configuredRoot.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String segment : normalized.split("/")) {
            if ("groovy".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSource(Path sourceRoot, String extension) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return false;
        }
        try (Stream<Path> paths = Files.find(
                sourceRoot,
                Integer.MAX_VALUE,
                (path, attributes) -> attributes.isRegularFile()
                        && path.getFileName().toString().endsWith(extension))) {
            return paths.findFirst().isPresent();
        } catch (IOException | UncheckedIOException ignored) {
            // IDE export remains best-effort when an otherwise valid source root cannot be scanned.
            return false;
        }
    }
}
