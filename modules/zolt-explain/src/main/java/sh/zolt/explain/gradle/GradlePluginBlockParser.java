package sh.zolt.explain.gradle;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Statically extracts plugin declarations from a Gradle plugins block. */
final class GradlePluginBlockParser {
    private static final Pattern ID_PLUGIN_PATTERN = Pattern.compile(
            "\\bid\\s*(?:\\(\\s*)?['\"]([^'\"]+)['\"]\\s*\\)?"
                    + "(?:\\s*version\\s*['\"]([^'\"]+)['\"])?"
                    + "(?:\\s*apply\\s*(?:\\(\\s*)?(true|false)\\s*\\)?)?");
    private static final Pattern KOTLIN_PLUGIN_CALL_PATTERN = Pattern.compile(
            "\\bkotlin\\s*\\(([^)]*)\\)(?:\\s*version\\s*(['\"])([^'\"]+)\\2)?"
                    + "(?:\\s*apply\\s*(?:\\(\\s*)?(true|false)\\s*\\)?)?");
    private static final Pattern KOTLIN_SELECTOR_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern ALIAS_PLUGIN_CALL_PATTERN = Pattern.compile(
            "\\balias\\s*\\(([^)]*)\\)"
                    + "(?:\\s*apply\\s*(?:\\(\\s*)?(true|false)\\s*\\)?)?");
    private static final Pattern BARE_ALIAS_PLUGIN_CALL_PATTERN = Pattern.compile(
            "(?m)(?:^|[;\\r\\n])\\s*alias\\s+(?!\\()([^\\s;]+)"
                    + "(?:\\s+apply\\s+(true|false))?\\s*(?=;|\\r?$)");
    private static final Pattern DEFAULT_ALIAS_ARGUMENT_PATTERN = Pattern.compile(
            "\\s*libs\\.plugins\\.([A-Za-z0-9_.]+)\\s*");
    private static final Pattern GROOVY_PLUGIN_PATTERN = Pattern.compile(
            "(?m)^\\s*([A-Za-z][A-Za-z0-9_-]*)\\s*$");
    private static final Pattern BACKTICK_PLUGIN_PATTERN = Pattern.compile(
            "`([A-Za-z][A-Za-z0-9_.-]*)`");

    List<GradlePluginInspection> plugins(
            String content,
            Map<String, GradlePluginInspection> catalogPlugins,
            String project,
            List<ExplainSignal> signals) {
        String block = GradleScriptBlocks.topLevelBlock(content, "plugins").orElse("");
        List<GradlePluginInspection> plugins = new ArrayList<>();
        Matcher idMatcher = ID_PLUGIN_PATTERN.matcher(block);
        while (idMatcher.find()) {
            addPlugin(plugins, new GradlePluginInspection(
                    idMatcher.group(1),
                    nullToEmpty(idMatcher.group(2)),
                    applied(idMatcher.group(3))));
        }
        Matcher kotlinCallMatcher = KOTLIN_PLUGIN_CALL_PATTERN.matcher(block);
        while (kotlinCallMatcher.find()) {
            addKotlinPlugin(kotlinCallMatcher, project, signals, plugins);
        }
        Matcher aliasCallMatcher = ALIAS_PLUGIN_CALL_PATTERN.matcher(block);
        while (aliasCallMatcher.find()) {
            addAliasPlugin(
                    aliasCallMatcher.group(1),
                    applied(aliasCallMatcher.group(2)),
                    catalogPlugins,
                    project,
                    signals,
                    plugins);
        }
        Matcher bareAliasCallMatcher = BARE_ALIAS_PLUGIN_CALL_PATTERN.matcher(block);
        while (bareAliasCallMatcher.find()) {
            addAliasPlugin(
                    bareAliasCallMatcher.group(1),
                    applied(bareAliasCallMatcher.group(2)),
                    catalogPlugins,
                    project,
                    signals,
                    plugins);
        }
        addAccessorPlugins(block, plugins);
        plugins.sort(Comparator.comparing(GradlePluginInspection::id)
                .thenComparing(GradlePluginInspection::version));
        return plugins;
    }

    private static void addKotlinPlugin(
            Matcher matcher,
            String project,
            List<ExplainSignal> signals,
            List<GradlePluginInspection> plugins) {
        String expression = matcher.group(1).strip();
        Optional<String> selector = literalKotlinSelector(expression);
        if (selector.isEmpty()) {
            signals.add(ExplainSignals.GRADLE_KOTLIN_PLUGIN_UNRESOLVED.signal(
                    project,
                    "Gradle kotlin(...) plugin selector `"
                            + (expression.isBlank() ? "<empty>" : expression)
                            + "` is computed and cannot be classified statically."));
            return;
        }
        addPlugin(plugins, new GradlePluginInspection(
                "org.jetbrains.kotlin." + selector.orElseThrow(),
                nullToEmpty(matcher.group(3)),
                applied(matcher.group(4))));
    }

    private static void addAliasPlugin(
            String rawExpression,
            boolean applied,
            Map<String, GradlePluginInspection> catalogPlugins,
            String project,
            List<ExplainSignal> signals,
            List<GradlePluginInspection> plugins) {
        String expression = rawExpression.strip();
        Matcher defaultAlias = DEFAULT_ALIAS_ARGUMENT_PATTERN.matcher(expression);
        if (!defaultAlias.matches()) {
            signals.add(ExplainSignals.GRADLE_PLUGIN_ALIAS_UNRESOLVED.signal(
                    project,
                    "Gradle plugin alias expression `"
                            + (expression.isBlank() ? "<empty>" : expression)
                            + "` cannot be resolved from the default version catalog."));
            return;
        }
        String alias = defaultAlias.group(1);
        GradlePluginInspection plugin = catalogPlugins.get(alias);
        if (plugin == null) {
            signals.add(ExplainSignals.GRADLE_PLUGIN_ALIAS_UNRESOLVED.signal(
                    project,
                    "Gradle plugin alias `libs.plugins." + alias
                            + "` was not resolved from the default version catalog."));
            return;
        }
        addPlugin(plugins, plugin.withApplied(applied));
    }

    private static void addAccessorPlugins(
            String block,
            List<GradlePluginInspection> plugins) {
        Matcher backtickMatcher = BACKTICK_PLUGIN_PATTERN.matcher(block);
        while (backtickMatcher.find()) {
            addPlugin(plugins, new GradlePluginInspection(backtickMatcher.group(1), ""));
        }
        Matcher groovyMatcher = GROOVY_PLUGIN_PATTERN.matcher(block);
        while (groovyMatcher.find()) {
            String id = groovyMatcher.group(1);
            if (!"id".equals(id)) {
                addPlugin(plugins, new GradlePluginInspection(id, ""));
            }
        }
    }

    private static void addPlugin(
            List<GradlePluginInspection> plugins,
            GradlePluginInspection candidate) {
        for (int index = 0; index < plugins.size(); index++) {
            GradlePluginInspection existing = plugins.get(index);
            if (!existing.id().equals(candidate.id())) {
                continue;
            }
            String version = existing.version().isBlank()
                    ? candidate.version()
                    : existing.version();
            boolean applied = existing.applied() || candidate.applied();
            if (!version.equals(existing.version()) || applied != existing.applied()) {
                plugins.set(index, new GradlePluginInspection(existing.id(), version, applied));
            }
            return;
        }
        plugins.add(candidate);
    }

    private static Optional<String> literalKotlinSelector(String expression) {
        if (expression.length() < 3) {
            return Optional.empty();
        }
        char quote = expression.charAt(0);
        if ((quote != '\'' && quote != '"') || expression.charAt(expression.length() - 1) != quote) {
            return Optional.empty();
        }
        String selector = expression.substring(1, expression.length() - 1);
        return KOTLIN_SELECTOR_PATTERN.matcher(selector).matches()
                ? Optional.of(selector)
                : Optional.empty();
    }

    private static boolean applied(String value) {
        return !"false".equalsIgnoreCase(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
