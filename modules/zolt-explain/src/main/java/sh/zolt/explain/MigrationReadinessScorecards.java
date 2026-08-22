package sh.zolt.explain;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleMigrationReadinessFindings;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenMigrationReadinessFindings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class MigrationReadinessScorecards {
    private static final List<String> CONCERNS = List.of(
            "repositories",
            "dependencies",
            "generated-sources",
            "resources",
            "tests",
            "coverage",
            "package",
            "publish",
            "ci");
    private static final List<DefaultConcern> MAVEN_DEFAULTS = List.of(
            new DefaultConcern("repositories", "pom repositories", "[repositories]", ""),
            new DefaultConcern("dependencies", "dependencies and dependencyManagement", "[dependencies] and [platforms]", ""),
            new DefaultConcern("generated-sources", "standard Maven source roots", "[generated.main] and [generated.test]", ""),
            new DefaultConcern("resources", "standard Maven resource roots", "[resources]", ""),
            new DefaultConcern("tests", "standard Maven test source roots", "[test.sources]", ""),
            new DefaultConcern("coverage", "no static coverage plugin required", "none required", ""),
            new DefaultConcern("package", "Maven jar/war packaging metadata", "[package]", ""),
            new DefaultConcern("publish", "publication metadata in pom.xml", "[publish]", ""),
            new DefaultConcern("ci", "locked/offline Zolt command sequence", "zolt check --target ci", ""));
    private static final List<DefaultConcern> GRADLE_DEFAULTS = List.of(
            new DefaultConcern("repositories", "repositories { mavenCentral() / maven { url ... } }", "[repositories]", ""),
            new DefaultConcern("dependencies", "simple dependency declarations and BOMs", "[dependencies], [platforms], and dependency policy", ""),
            new DefaultConcern("generated-sources", "declared sourceSets generated roots", "[generated.main] and [generated.test]", ""),
            new DefaultConcern("resources", "declared resource roots", "[resources]", ""),
            new DefaultConcern("tests", "JUnit Platform test execution", "[test.sources]", ""),
            new DefaultConcern("coverage", "coverage is explicit, not finalizedBy task behavior", "zolt coverage", ""),
            new DefaultConcern("package", "jar, war, Spring Boot jar, and Spring Boot WAR package modes", "[package]", ""),
            new DefaultConcern("publish", "publication metadata and dry-run routing", "[publish] and zolt publish --dry-run", ""),
            new DefaultConcern("ci", "locked/offline Zolt command sequence", "zolt check --target ci", ""));
    private static final Set<String> GRADLE_COVERAGE_GAP_SIGNALS = Set.of(
            "gradle.project.missing-build-file",
            "gradle.project.build-file-name-unresolved",
            "gradle.plugin.convention",
            "gradle.script-plugin.apply-from",
            "gradle.plugin.conditional-apply",
            "gradle.settings.include-conditional",
            "gradle.start-parameter.mutation",
            "gradle.task-mutation.detected");

    private MigrationReadinessScorecards() {
    }

    public static MigrationReadinessScorecard from(MavenInspectionResult result) {
        List<MigrationReadinessFinding> findings = new ArrayList<>();
        findings.addAll(defaultFindings(MAVEN_DEFAULTS));
        for (ExplainSignal signal : result.signals()) {
            findings.add(MavenMigrationReadinessFindings.map(signal));
        }
        return build("maven", result.root(), findings);
    }

    public static MigrationReadinessScorecard from(GradleInspectionResult result) {
        List<MigrationReadinessFinding> signalFindings = new ArrayList<>();
        for (ExplainSignal signal : result.signals()) {
            signalFindings.add(GradleMigrationReadinessFindings.map(signal));
        }
        List<MigrationReadinessFinding> findings = new ArrayList<>(
                gradleDefaultFindings(result.signals(), signalFindings));
        findings.addAll(signalFindings);
        return build("gradle", result.root(), findings);
    }

    private static List<MigrationReadinessFinding> defaultFindings(List<DefaultConcern> defaults) {
        return defaults.stream()
                .map(def -> MigrationReadinessFindings.defaultFinding(
                        def.name(),
                        def.sourcePattern(),
                        def.zoltPrimitive(),
                        def.followUp()))
                .toList();
    }

    private static List<MigrationReadinessFinding> gradleDefaultFindings(
            List<ExplainSignal> signals,
            List<MigrationReadinessFinding> signalFindings) {
        boolean hasCoverageGap = signals.stream()
                .map(ExplainSignal::id)
                .anyMatch(GRADLE_COVERAGE_GAP_SIGNALS::contains);
        if (!hasCoverageGap) {
            return defaultFindings(GRADLE_DEFAULTS);
        }
        return GRADLE_DEFAULTS.stream()
                .map(def -> hasConcernEvidence(def.name(), signalFindings)
                        ? MigrationReadinessFindings.defaultFinding(
                                def.name(),
                                def.sourcePattern(),
                                def.zoltPrimitive(),
                                def.followUp())
                        : MigrationReadinessFindings.unknownFinding(def.name()))
                .toList();
    }

    private static boolean hasConcernEvidence(
            String concern,
            List<MigrationReadinessFinding> findings) {
        return findings.stream().anyMatch(finding -> concernFor(finding).equals(concern));
    }

    private static MigrationReadinessScorecard build(
            String source,
            Path root,
            List<MigrationReadinessFinding> findings) {
        List<MigrationReadinessConcern> concerns = CONCERNS.stream()
                .map(name -> concern(name, findings))
                .toList();
        return new MigrationReadinessScorecard(source, root, status(concerns), concerns, checklist(concerns));
    }

    private static MigrationReadinessConcern concern(String name, List<MigrationReadinessFinding> findings) {
        List<MigrationReadinessFinding> concernFindings = findings.stream()
                .filter(finding -> concernFor(finding).equals(name))
                .sorted(Comparator
                        .comparingInt((MigrationReadinessFinding finding) -> categoryRank(finding.category()))
                        .thenComparingInt(finding -> severityRank(finding.severity()))
                        .thenComparing(MigrationReadinessFinding::sourcePattern)
                        .thenComparing(MigrationReadinessFinding::zoltPrimitive)
                        .thenComparing(MigrationReadinessFinding::followUp)
                        .thenComparing(MigrationReadinessFinding::message))
                .toList();
        return new MigrationReadinessConcern(name, concernStatus(concernFindings), concernFindings);
    }

    private static String concernFor(MigrationReadinessFinding finding) {
        String sourcePattern = finding.sourcePattern();
        if (sourcePattern.startsWith("concern:")) {
            int end = sourcePattern.indexOf(' ');
            return sourcePattern.substring("concern:".length(), end);
        }
        return "dependencies";
    }

    private static String status(List<MigrationReadinessConcern> concerns) {
        if (concerns.stream().anyMatch(concern -> concern.status().equals("blocked")
                || concern.status().equals("unsupported"))) {
            return "blocked";
        }
        if (concerns.stream().anyMatch(concern -> concern.status().equals("non-deterministic"))) {
            return "non-deterministic";
        }
        if (concerns.stream().anyMatch(concern -> concern.status().equals("unknown"))) {
            return "unknown";
        }
        if (concerns.stream().anyMatch(concern -> concern.status().equals("planned"))) {
            return "planned";
        }
        return "supported";
    }

    private static String concernStatus(List<MigrationReadinessFinding> findings) {
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.UNSUPPORTED)) {
            return "unsupported";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.BLOCKED)) {
            return "blocked";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.NON_DETERMINISTIC)) {
            return "non-deterministic";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.UNKNOWN)) {
            return "unknown";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.PLANNED)) {
            return "planned";
        }
        return "supported";
    }

    private static List<String> checklist(List<MigrationReadinessConcern> concerns) {
        return concerns.stream()
                .flatMap(concern -> concern.findings().stream())
                .filter(finding -> finding.category() != MigrationReadinessCategory.SUPPORTED)
                .map(MigrationReadinessFinding::nextStep)
                .distinct()
                .sorted()
                .toList();
    }

    private static int categoryRank(MigrationReadinessCategory category) {
        return switch (category) {
            case BLOCKED -> 0;
            case UNSUPPORTED -> 1;
            case NON_DETERMINISTIC -> 2;
            case UNKNOWN -> 3;
            case PLANNED -> 4;
            case SUPPORTED -> 5;
        };
    }

    private static int severityRank(ExplainSignal.Severity severity) {
        return switch (severity) {
            case BLOCK -> 0;
            case UNKNOWN -> 1;
            case WARN -> 2;
            case OK -> 3;
        };
    }

    private record DefaultConcern(
            String name,
            String sourcePattern,
            String zoltPrimitive,
            String followUp) {
    }
}
