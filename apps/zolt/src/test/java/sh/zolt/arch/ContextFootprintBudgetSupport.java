package sh.zolt.arch;

import static sh.zolt.arch.ArchitectureBudgetSupport.sourceRoots;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ContextFootprintBudgetSupport {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    private ContextFootprintBudgetSupport() {}

    static List<Budget> readBudgets(Path path) throws IOException {
        List<Budget> budgets = new ArrayList<>();
        Set<BudgetKey> keys = new LinkedHashSet<>();
        for (String line : Files.readAllLines(path)) {
            Optional<Budget> budget = parseBudgetLine(line);
            budget.ifPresent(value -> {
                BudgetKey key = new BudgetKey(canonicalRoot(value.rootPattern()), value.packageName());
                if (!keys.add(key)) {
                    throw new IllegalArgumentException(
                            "Duplicate context footprint budget rule: " + key);
                }
                budgets.add(value);
            });
        }
        return List.copyOf(budgets);
    }

    static List<PackageFootprint> packageFootprints(List<Budget> budgets) throws IOException {
        Map<Path, Path> roots = new LinkedHashMap<>();
        for (Budget budget : budgets) {
            for (Path sourceRoot : sourceRoots(budget.rootPattern())) {
                Path canonical = canonicalRoot(sourceRoot);
                roots.putIfAbsent(canonical, canonical);
            }
        }
        List<PackageFootprint> footprints = packageFootprints(roots.values());
        validatePackageOverrides(budgets, footprints);
        return List.copyOf(footprints);
    }

    static List<PackageFootprint> packageFootprints(Budget budget) throws IOException {
        return packageFootprints(sourceRoots(budget.rootPattern()));
    }

    static Budget budgetFor(PackageFootprint footprint, List<Budget> budgets) throws IOException {
        Objects.requireNonNull(footprint, "Package footprint is required.");
        Objects.requireNonNull(budgets, "Context footprint budgets are required.");
        List<Budget> generic = new ArrayList<>();
        for (Budget budget : budgets) {
            if (!budget.isPackageOverride() && matchesRoot(footprint, budget)) {
                generic.add(budget);
            }
        }
        if (generic.size() != 1) {
            if (generic.isEmpty()) {
                throw new IllegalStateException(
                        "No generic context footprint budget matches "
                                + footprint.root() + " " + footprint.packageName() + ".");
            }
            throw ambiguous(footprint, generic);
        }

        List<Budget> overrides = budgets.stream()
                .filter(Budget::isPackageOverride)
                .filter(budget -> budget.packageName().orElseThrow().equals(footprint.packageName()))
                .filter(budget -> canonicalRoot(budget.rootPattern()).equals(footprint.sourceRoot()))
                .toList();
        if (overrides.size() > 1) {
            throw ambiguous(footprint, overrides);
        }
        if (overrides.size() == 1) {
            return overrides.getFirst();
        }
        return generic.getFirst();
    }

    static void validatePackageOverrides(
            List<Budget> budgets,
            List<PackageFootprint> footprints) {
        Objects.requireNonNull(budgets, "Context footprint budgets are required.");
        Objects.requireNonNull(footprints, "Package footprints are required.");
        for (Budget budget : budgets) {
            if (!budget.isPackageOverride()) {
                continue;
            }
            Path root = canonicalRoot(budget.rootPattern());
            String packageName = budget.packageName().orElseThrow();
            long matches = footprints.stream()
                    .filter(footprint -> footprint.sourceRoot().equals(root))
                    .filter(footprint -> footprint.packageName().equals(packageName))
                    .count();
            if (matches != 1) {
                throw new IllegalStateException(
                        "Package-specific context footprint budget `"
                                + rootKey(root) + "|" + packageName
                                + "` matched " + matches + " package footprints; expected exactly one.");
            }
        }
    }

    private static List<PackageFootprint> packageFootprints(Iterable<Path> sourceRoots)
            throws IOException {
        Map<PackageKey, PackageFootprintBuilder> footprints = new LinkedHashMap<>();
        for (Path sourceRoot : sourceRoots) {
            Path canonicalRoot = canonicalRoot(sourceRoot);
            for (Path javaFile : ArchitectureSourceFiles.javaFiles(List.of(sourceRoot))) {
                String packageName = packageName(javaFile);
                PackageKey key = new PackageKey(canonicalRoot, packageName);
                footprints.computeIfAbsent(
                                key,
                                ignored -> new PackageFootprintBuilder(
                                        canonicalRoot, packageName))
                        .add(lineCount(javaFile));
            }
        }
        return footprints.values().stream()
                .map(PackageFootprintBuilder::build)
                .sorted(Comparator.comparing(PackageFootprint::root)
                        .thenComparing(footprint -> footprint.sourceRoot().toString())
                        .thenComparing(PackageFootprint::packageName))
                .toList();
    }

    static String violation(PackageFootprint footprint, Budget budget) {
        return footprint.root()
                + " "
                + footprint.packageName()
                + " has "
                + footprint.files()
                + " files and "
                + footprint.lines()
                + " lines; budget is "
                + budget.maxFiles()
                + " files and "
                + budget.maxLines()
                + " lines";
    }

    static void writeSource(Path path, String packageName, int count) throws IOException {
        Files.createDirectories(path.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("package " + packageName + ";");
        for (int index = 1; index < count; index++) {
            lines.add("// line " + index);
        }
        Files.write(path, lines);
    }

    private static Optional<Budget> parseBudgetLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        String[] parts = trimmed.split("\\|");
        if (parts.length != 3 && parts.length != 4) {
            throw new IllegalArgumentException("Invalid context footprint budget line: " + line);
        }
        Path root = Path.of(parts[0]).normalize();
        if (parts.length == 3) {
            return Optional.of(new Budget(
                    root,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        }
        return Optional.of(new Budget(
                root,
                Optional.of(parts[1].trim()),
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3])));
    }

    private static boolean matchesRoot(PackageFootprint footprint, Budget budget)
            throws IOException {
        return sourceRoots(budget.rootPattern()).stream()
                .map(ContextFootprintBudgetSupport::canonicalRoot)
                .anyMatch(footprint.sourceRoot()::equals);
    }

    private static Path canonicalRoot(Path root) {
        Path resolved = root.isAbsolute()
                ? root
                : RepositoryPaths.root().resolve(root);
        return resolved.toAbsolutePath().normalize();
    }

    private static String rootKey(Path root) {
        Path canonical = canonicalRoot(root);
        Path repository = canonicalRoot(RepositoryPaths.root());
        Path display = canonical.startsWith(repository)
                ? repository.relativize(canonical)
                : canonical;
        return display.toString().replace('\\', '/');
    }

    private static IllegalStateException ambiguous(
            PackageFootprint footprint,
            List<Budget> budgets) {
        return new IllegalStateException(
                "Ambiguous context footprint budgets for "
                        + footprint.root() + " " + footprint.packageName() + ": " + budgets);
    }

    private static String packageName(Path path) throws IOException {
        for (String line : Files.readAllLines(path)) {
            Matcher matcher = PACKAGE_PATTERN.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return "(default)";
    }

    private static int lineCount(Path path) {
        try (Stream<String> lines = Files.lines(path)) {
            return Math.toIntExact(lines.count());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not count lines in " + path, exception);
        }
    }

    record Budget(
            Path rootPattern,
            Optional<String> packageName,
            int maxFiles,
            int maxLines) {
        Budget(Path rootPattern, int maxFiles, int maxLines) {
            this(rootPattern, Optional.empty(), maxFiles, maxLines);
        }

        Budget {
            rootPattern = Objects.requireNonNull(
                    rootPattern, "Context footprint root pattern is required.").normalize();
            packageName = Objects.requireNonNull(
                    packageName, "Context footprint package selector is required.");
            if (packageName.isPresent()) {
                String value = packageName.orElseThrow();
                if (value.isBlank()) {
                    throw new IllegalArgumentException(
                            "Context footprint package selector must not be blank.");
                }
                if (rootPattern.toString().contains("*")) {
                    throw new IllegalArgumentException(
                            "Package-specific context footprint budgets require an exact root.");
                }
            }
        }

        boolean isPackageOverride() {
            return packageName.isPresent();
        }
    }

    private record BudgetKey(Path rootPattern, Optional<String> packageName) {
        @Override
        public String toString() {
            return rootKey(rootPattern)
                    + packageName.map(value -> "|" + value).orElse("");
        }
    }

    private record PackageKey(Path sourceRoot, String packageName) {
    }

    record PackageFootprint(Path sourceRoot, String packageName, int files, int lines) {
        PackageFootprint {
            sourceRoot = canonicalRoot(sourceRoot);
        }

        String root() {
            return rootKey(sourceRoot);
        }
    }

    private static final class PackageFootprintBuilder {
        private final Path sourceRoot;
        private final String packageName;
        private int files;
        private int lines;

        private PackageFootprintBuilder(Path sourceRoot, String packageName) {
            this.sourceRoot = sourceRoot;
            this.packageName = packageName;
        }

        private void add(int lineCount) {
            files++;
            lines += lineCount;
        }

        private PackageFootprint build() {
            return new PackageFootprint(sourceRoot, packageName, files, lines);
        }
    }
}
