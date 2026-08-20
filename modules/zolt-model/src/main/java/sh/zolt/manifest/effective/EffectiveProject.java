package sh.zolt.manifest.effective;

import java.util.Objects;
import sh.zolt.manifest.authored.ProjectLocalDomains;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/** One complete project view after workspace sharing and identity inheritance. */
public record EffectiveProject(
        EffectiveProjectIdentity identity,
        EffectiveSharedConfiguration shared,
        ProjectLocalDomains local) {
    public EffectiveProject {
        identity = Objects.requireNonNull(identity, "Effective project identity must not be null.");
        shared = Objects.requireNonNull(shared, "Effective shared configuration must not be null.");
        local = Objects.requireNonNull(local, "Effective project-local domains must not be null.");
        validateJavaShape(identity, shared.toolchains(), local.packaging().bom().isPresent());
    }

    private static void validateJavaShape(
            EffectiveProjectIdentity identity,
            EffectiveToolchains toolchains,
            boolean bom) {
        boolean hasJavaRelease = identity.javaRelease().isPresent();
        boolean hasJavaRuntimes = toolchains.mainJava().isPresent()
                && toolchains.testJava().isPresent();
        if (bom && (hasJavaRelease || hasJavaRuntimes)) {
            throw new IllegalArgumentException(
                    "An effective BOM cannot have a project Java release or Java runtimes.");
        }
        if (!bom && (!hasJavaRelease || !hasJavaRuntimes)) {
            throw new IllegalArgumentException(
                    "An effective non-BOM project requires a Java release and main/test runtimes.");
        }
        if (bom) {
            return;
        }

        int projectRelease = identity.javaRelease().orElseThrow().value().value();
        EffectiveJavaRuntime main = toolchains.mainJava().orElseThrow();
        requireCompatible(
                mainRelease(main), projectRelease, "Effective main Java runtime");
        EffectiveTestJavaRuntime test = toolchains.testJava().orElseThrow();
        if (test instanceof EffectiveTestJavaRuntime.Requested requested) {
            requireCompatible(
                    requested.version().value(),
                    projectRelease,
                    "Effective test Java runtime");
        }
    }

    private static JavaFeatureRelease mainRelease(EffectiveJavaRuntime runtime) {
        return switch (runtime) {
            case EffectiveJavaRuntime.System system -> system.requiredRelease().value();
            case EffectiveJavaRuntime.Requested requested -> requested.version().value();
        };
    }

    private static void requireCompatible(
            JavaFeatureRelease runtimeRelease,
            int projectRelease,
            String label) {
        if (runtimeRelease.value() < projectRelease) {
            throw new IllegalArgumentException(
                    label + " release " + runtimeRelease
                            + " cannot execute project Java release " + projectRelease + ".");
        }
    }
}
