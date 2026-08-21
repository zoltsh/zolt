package sh.zolt.manifest.adapter;

import java.util.Optional;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.effective.EffectiveCoverage;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.CoverageSettings;

/** Projects final {@code [coverage]} floors onto the legacy {@link CoverageSettings}. */
public final class ProjectConfigCoverage {
    private ProjectConfigCoverage() {
    }

    /**
     * The floors exactly as authored in one manifest, with no workspace inheritance. This is the
     * direct replacement for the legacy per-file coverage read, and it works for a virtual workspace
     * root, which has {@code [coverage]} but no {@code [project]} to compose.
     */
    public static CoverageSettings authored(Optional<AuthoredCoverage> coverage) {
        return coverage
                .map(floors -> new CoverageSettings(
                        percentage(floors.line()),
                        percentage(floors.branch()),
                        percentage(floors.instruction()),
                        percentage(floors.method())))
                .orElseGet(CoverageSettings::none);
    }

    /** The floors after workspace inheritance, for a composed project view. */
    public static CoverageSettings effective(EffectiveCoverage coverage) {
        return new CoverageSettings(
                effectivePercentage(coverage.line()),
                effectivePercentage(coverage.branch()),
                effectivePercentage(coverage.instruction()),
                effectivePercentage(coverage.method()));
    }

    private static Optional<Double> percentage(Optional<CoveragePercentage> value) {
        return value.map(CoveragePercentage::value);
    }

    private static Optional<Double> effectivePercentage(
            Optional<EffectiveValue<CoveragePercentage>> value) {
        return value.map(EffectiveValue::value).map(CoveragePercentage::value);
    }
}
