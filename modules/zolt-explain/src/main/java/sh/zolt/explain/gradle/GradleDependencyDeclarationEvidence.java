package sh.zolt.explain.gradle;

import java.util.regex.Pattern;

/** Proves that every top-level Gradle dependency declaration is in the static parser's safe subset. */
final class GradleDependencyDeclarationEvidence {
    private static final String CONFIGURATION = "(?:api|implementation|compileOnly|runtimeOnly|"
            + "testImplementation|testCompileOnly|testRuntimeOnly)";
    private static final String COORDINATE =
            "['\"][A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9][A-Za-z0-9_.+-]*['\"]";
    private static final String CATALOG_ALIAS = "libs\\.(?:bundles\\.)?[A-Za-z0-9_.-]+";
    private static final String NOTATION = "(?:" + COORDINATE + "|" + CATALOG_ALIAS + ")";
    private static final Pattern DECLARATION = Pattern.compile(
            "\\s*" + CONFIGURATION + "\\s*(?:\\(\\s*" + NOTATION + "\\s*\\)|" + NOTATION
                    + ")\\s*;?\\s*");

    private GradleDependencyDeclarationEvidence() {
    }

    static boolean fullyMapped(String content) {
        String source = GradleSourceComments.stripComments(content);
        for (String block : GradleScriptBlocks.topLevelBlocks(source, "dependencies")) {
            if (!GradleScriptBlocks.withoutNestedBlocks(block).equals(block)) {
                return false;
            }
            for (String line : block.lines().toList()) {
                if (!line.isBlank() && !DECLARATION.matcher(line).matches()) {
                    return false;
                }
            }
        }
        return true;
    }
}
