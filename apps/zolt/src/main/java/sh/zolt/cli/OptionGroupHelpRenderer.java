package sh.zolt.cli;

import sh.zolt.cli.console.ConsoleStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine.Help;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.IHelpSectionRenderer;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

final class OptionGroupHelpRenderer implements IHelpSectionRenderer {
    private static final String DEFAULT_HEADING = "Options";
    private static final List<OptionGroup> GROUPS = List.of(
            new OptionGroup(DEFAULT_HEADING, List.of(
                    "--color",
                    "--progress",
                    "--no-progress",
                    "--quiet",
                    "--list",
                    "--help",
                    "--version")),
            new OptionGroup("Workspace Selection", List.of(
                    "--workspace",
                    "--all",
                    "--member",
                    "--members")),
            new OptionGroup("Test Selection", List.of(
                    "--suite",
                    "--shard",
                    "--shard-count",
                    "--balance-from",
                    "--test",
                    "--tests",
                    "--include-tag",
                    "--exclude-tag")),
            new OptionGroup("Test Runtime", List.of(
                    "--jvm-arg",
                    "--test-workers")),
            new OptionGroup("Output", List.of(
                    "--format",
                    "--reports-dir",
                    "--coverage-dir",
                    "--test-event",
                    "--no-xml",
                    "--no-html",
                    "--exec-file",
                    "--xml-report",
                    "--html-dir")),
            new OptionGroup("Resolution", List.of(
                    "--offline",
                    "--locked",
                    "--repository-overlay",
                    "--no-local-overlays",
                    "--no-resolve")),
            new OptionGroup("Diagnostics", List.of(
                    "--profile-tests",
                    "--profile-dir",
                    "--profile-top",
                    "--profile-min",
                    "--timings",
                    "--timings-format")));

    private final Supplier<ConsoleStyle> styles;

    OptionGroupHelpRenderer(Supplier<ConsoleStyle> styles) {
        this.styles = styles;
    }

    @Override
    public String render(Help help) {
        List<OptionSpec> visibleOptions = visibleOptions(help);
        if (visibleOptions.isEmpty()) {
            return "";
        }

        Map<OptionGroup, List<OptionSpec>> grouped = groupedOptions(visibleOptions);
        long nonDefaultGroups = grouped.entrySet().stream()
                .filter(entry -> !DEFAULT_HEADING.equals(entry.getKey().heading()))
                .filter(entry -> !entry.getValue().isEmpty())
                .count();
        if (nonDefaultGroups == 0) {
            return renderOptions(help, grouped.get(GROUPS.getFirst()));
        }

        StringBuilder output = new StringBuilder();
        boolean firstGroup = true;
        for (Map.Entry<OptionGroup, List<OptionSpec>> entry : grouped.entrySet()) {
            List<OptionSpec> options = entry.getValue();
            if (options.isEmpty()) {
                continue;
            }
            if (DEFAULT_HEADING.equals(entry.getKey().heading())) {
                output.append(renderOptions(help, options));
                firstGroup = false;
                continue;
            }
            if (!firstGroup) {
                output.append(System.lineSeparator());
            }
            output.append(styles.get().helpHeading(entry.getKey().heading() + ":"))
                    .append(System.lineSeparator());
            output.append(renderOptions(help, options));
            firstGroup = false;
        }
        return output.toString();
    }

    private static List<OptionSpec> visibleOptions(Help help) {
        return help.commandSpec().options().stream()
                .filter(option -> !option.hidden())
                .filter(option -> option.group() == null)
                .toList();
    }

    private static Map<OptionGroup, List<OptionSpec>> groupedOptions(List<OptionSpec> options) {
        Map<OptionGroup, List<OptionSpec>> grouped = new LinkedHashMap<>();
        for (OptionGroup group : GROUPS) {
            grouped.put(group, new ArrayList<>());
        }
        OptionGroup defaultGroup = GROUPS.getFirst();
        for (OptionSpec option : options) {
            OptionGroup group = groupFor(option).orElse(defaultGroup);
            grouped.get(group).add(option);
        }
        grouped.values().forEach(OptionGroupHelpRenderer::sortOptions);
        return grouped;
    }

    private static Optional<OptionGroup> groupFor(OptionSpec option) {
        for (OptionGroup group : GROUPS) {
            if (group.matches(option)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    private static void sortOptions(List<OptionSpec> options) {
        options.sort(Comparator
                .comparingInt(OptionGroupHelpRenderer::groupOrder)
                .thenComparing(OptionSpec::longestName));
    }

    private static int groupOrder(OptionSpec option) {
        for (OptionGroup group : GROUPS) {
            int index = group.indexOf(option);
            if (index >= 0) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private String renderOptions(Help help, List<OptionSpec> options) {
        List<PositionalParamSpec> positionals = List.of();
        Help.ColorScheme colorScheme = new Help.ColorScheme.Builder(Ansi.OFF).build();
        Help.Layout layout = help.createDefaultLayout(options, positionals, colorScheme);
        String optionList = help.optionListExcludingGroups(options, layout, null, help.parameterLabelRenderer());
        return HelpOptionHighlighter.highlight(
                HelpMetavarLabelNormalizer.normalize(
                        HelpOptionValueSeparator.useSpaceForRequiredLongOptionValues(optionList)),
                styles.get());
    }

    private record OptionGroup(String heading, List<String> optionNames) {
        private boolean matches(OptionSpec option) {
            return indexOf(option) >= 0;
        }

        private int indexOf(OptionSpec option) {
            for (int index = 0; index < optionNames.size(); index++) {
                if (hasName(option, optionNames.get(index))) {
                    return index;
                }
            }
            return -1;
        }

        private static boolean hasName(OptionSpec option, String name) {
            for (String optionName : option.names()) {
                if (optionName.equals(name)) {
                    return true;
                }
            }
            return false;
        }
    }
}
