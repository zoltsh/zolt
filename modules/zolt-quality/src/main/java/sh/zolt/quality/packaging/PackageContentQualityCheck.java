package sh.zolt.quality.packaging;

import static sh.zolt.quality.QualityCheckService.PACKAGE_CONTENTS;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageevidence.PackageEvidenceVerification;
import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanDependency;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packageplan.PackagePlanWarning;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckText;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class PackageContentQualityCheck {
    private final PackagePlanService packagePlanService;
    private final PackageEvidenceVerifier packageEvidenceVerifier;

    PackageContentQualityCheck(
            PackagePlanService packagePlanService,
            PackageEvidenceManifestReader packageEvidenceManifestReader) {
        this.packagePlanService = packagePlanService;
        this.packageEvidenceVerifier =
                new PackageEvidenceVerifier(packageEvidenceManifestReader);
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config,
            Path lockfilePath,
            boolean requirePackage) {
        if (!Files.isRegularFile(lockfilePath)) {
            return List.of(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    "zolt.lock",
                    "Package content diagnostics require zolt.lock.",
                    member.isPresent() ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`."));
        }
        PackagePlan plan;
        try {
            plan = packagePlanService.plan(projectRoot, config, lockfilePath);
        } catch (LockfileReadException exception) {
            return List.of(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    "zolt.lock",
                    exception.getMessage(),
                    member.isPresent() ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`."));
        } catch (PackageException exception) {
            return List.of(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    "package inputs",
                    exception.getMessage(),
                    "Restore the required build inputs, then run `zolt build` and `zolt package`."));
        }
        return check(member, config, plan, requirePackage);
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            ProjectConfig config,
            PackagePlan plan,
            boolean requirePackage) {
        List<QualityCheckResult> failures =
                planFailures(member, plan);
        if (failures.isEmpty()) {
            return successfulPlanResults(member, config, plan, requirePackage);
        }
        return failures;
    }

    private static List<QualityCheckResult> planFailures(
            Optional<String> member,
            PackagePlan plan) {
        List<QualityCheckResult> results = new ArrayList<>();
        for (PackagePlanWarning warning : plan.warnings()) {
            results.add(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    warning.subject(),
                    warning.message(),
                    warning.nextStep()));
        }
        for (PackagePlanDependency dependency : plan.dependencies()) {
            if (!"unsupported".equals(dependency.disposition())) {
                continue;
            }
            results.add(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    dependency.coordinate(),
                    "Package rule `"
                            + dependency.ruleName()
                            + "` cannot describe this dependency: "
                            + dependency.reason()
                            + ".",
                    "Install the framework package plan rules for mode `"
                            + plan.mode().configValue()
                            + "` before treating package contents as verified."));
        }
        return List.copyOf(results);
    }

    private List<QualityCheckResult> successfulPlanResults(
            Optional<String> member,
            ProjectConfig config,
            PackagePlan plan,
            boolean requirePackage) {
        if (requirePackage && !Files.isRegularFile(plan.archivePath())) {
            return List.of(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    QualityCheckText.displayPath(plan.projectRoot(), plan.archivePath()),
                    "CI context requires the configured package artifact, but it is missing.",
                    member.isPresent()
                            ? "Run `zolt package --workspace` before `zolt check --workspace --context ci --require-package`."
                            : "Run `zolt package` before `zolt check --context ci --require-package`."));
        }
        Optional<QualityCheckResult> staleEvidence =
                stalePackageEvidence(member, plan);
        if (staleEvidence.isPresent()) {
            return List.of(staleEvidence.orElseThrow());
        }
        List<QualityCheckResult> results = new ArrayList<>();
        long policyEffects = plan.dependencies().stream()
                .filter(dependency -> !dependency.policies().isEmpty())
                .count();
        String policyMessage = policyEffects == 0
                ? ""
                : " " + policyEffects + " dependencies include dependency policy effects.";
        results.add(QualityCheckResult.passed(
                PACKAGE_CONTENTS,
                member,
                config.project().name(),
                "Package mode `"
                        + plan.mode().configValue()
                        + "` has "
                        + plan.dependencies().size()
                        + " dependency dispositions."
                        + policyMessage));
        results.addAll(packageContentRuleDiagnostics(member, plan));
        return List.copyOf(results);
    }

    private static List<QualityCheckResult> packageContentRuleDiagnostics(
            Optional<String> member,
            PackagePlan plan) {
        Map<PackageContentRuleKey, PackageContentRuleStats> stats = new TreeMap<>();
        for (PackagePlanDependency dependency : plan.dependencies()) {
            PackageContentRuleKey key = new PackageContentRuleKey(
                    dependency.ruleName(),
                    dependency.scope().lockfileName(),
                    dependency.disposition(),
                    locationShape(dependency.location()));
            stats.computeIfAbsent(key, ignored -> new PackageContentRuleStats()).add(dependency);
        }
        List<QualityCheckResult> results = new ArrayList<>();
        for (Map.Entry<PackageContentRuleKey, PackageContentRuleStats> entry : stats.entrySet()) {
            PackageContentRuleKey key = entry.getKey();
            PackageContentRuleStats value = entry.getValue();
            String policyMessage = value.policyEffects() == 0
                    ? ""
                    : " "
                            + value.policyEffects()
                            + " "
                            + QualityCheckText.verb(value.policyEffects(), "includes", "include")
                            + " dependency policy effects.";
            results.add(QualityCheckResult.passed(
                    PACKAGE_CONTENTS,
                    member,
                    "rule:" + key.ruleName(),
                    value.count()
                            + " "
                            + QualityCheckText.plural(value.count(), "dependency", "dependencies")
                            + " "
                            + QualityCheckText.verb(value.count(), "uses", "use")
                            + " package rule `"
                            + key.ruleName()
                            + "` with scope `"
                            + key.scope()
                            + "`, disposition `"
                            + key.disposition()
                            + "`, and location `"
                            + key.location()
                            + "`."
                            + policyMessage));
        }
        return List.copyOf(results);
    }

    private static String locationShape(String location) {
        if (location == null || location.isBlank()) {
            return "none";
        }
        int slash = location.lastIndexOf('/');
        if (slash <= 0 || location.endsWith("/")) {
            return location;
        }
        return location.substring(0, slash + 1) + "*";
    }

    private Optional<QualityCheckResult> stalePackageEvidence(Optional<String> member, PackagePlan plan) {
        Path archive = plan.archivePath();
        if (!Files.exists(archive)) {
            return Optional.empty();
        }
        Path root = plan.projectRoot().toAbsolutePath().normalize();
        Path manifestPath = PackageEvidenceManifestWriter.evidenceManifestPath(archive);
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.of(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    QualityCheckText.displayPath(root, archive),
                    "Package artifact exists, but package evidence manifest is missing.",
                    "Run `zolt package` to regenerate " + QualityCheckText.displayPath(root, manifestPath) + "."));
        }
        try {
            PackageEvidenceVerification verification =
                    packageEvidenceVerifier.verify(root, plan, manifestPath);
            if (!verification.valid()) {
                return Optional.of(QualityCheckResult.failed(
                        PACKAGE_CONTENTS,
                        member,
                        QualityCheckText.displayPath(root, manifestPath),
                        "Package evidence is stale for `"
                                + QualityCheckText.displayPath(root, archive)
                                + "`: "
                                + String.join("; ", verification.problems())
                                + ".",
                        "Run `zolt package` to regenerate the artifact and evidence manifest."));
            }
        } catch (PackageException exception) {
            return Optional.of(QualityCheckResult.failed(
                    PACKAGE_CONTENTS,
                    member,
                    QualityCheckText.displayPath(root, manifestPath),
                    exception.getMessage(),
                    "Run `zolt package` to regenerate package evidence."));
        }
        return Optional.empty();
    }

    private record PackageContentRuleKey(
            String ruleName,
            String scope,
            String disposition,
            String location) implements Comparable<PackageContentRuleKey> {
        @Override
        public int compareTo(PackageContentRuleKey other) {
            int rule = ruleName.compareTo(other.ruleName);
            if (rule != 0) {
                return rule;
            }
            int scopeComparison = scope.compareTo(other.scope);
            if (scopeComparison != 0) {
                return scopeComparison;
            }
            int dispositionComparison = disposition.compareTo(other.disposition);
            if (dispositionComparison != 0) {
                return dispositionComparison;
            }
            return location.compareTo(other.location);
        }
    }

    private static final class PackageContentRuleStats {
        private long count;
        private long policyEffects;

        void add(PackagePlanDependency dependency) {
            count++;
            if (!dependency.policies().isEmpty()) {
                policyEffects++;
            }
        }

        long count() {
            return count;
        }

        long policyEffects() {
            return policyEffects;
        }
    }
}
