package sh.zolt.manifest.effective;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredToolchains;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureRelease;
import sh.zolt.project.toolchain.ToolchainPolicy;

/** Applies project-derived and built-in defaults to standalone toolchain requests. */
final class EffectiveToolchainsComposer {
    EffectiveToolchains compose(
            AuthoredToolchains authored,
            EffectiveProjectIdentity identity,
            String manifestPath,
            boolean bom) {
        Optional<EffectiveValue<ZoltVersionPin>> zolt = authored.zolt()
                .map(value -> EffectiveValue.authored(
                        value, source(manifestPath, "toolchain", "zolt", "version")));
        if (bom) {
            return EffectiveToolchains.withoutJava(zolt);
        }

        EffectiveValue<JavaFeatureRelease> projectRelease =
                identity.javaRelease().orElseThrow();
        EffectiveJavaRuntime main = authored.mainJava()
                .<EffectiveJavaRuntime>map(value -> requestedMain(
                        value, projectRelease, manifestPath))
                .orElseGet(() -> new EffectiveJavaRuntime.System(projectRelease));
        EffectiveTestJavaRuntime test = authored.testJava()
                .<EffectiveTestJavaRuntime>map(value -> requestedTest(
                        value, main, projectRelease, manifestPath))
                .orElseGet(() -> new EffectiveTestJavaRuntime.SameAsMain(main));
        return new EffectiveToolchains(zolt, Optional.of(main), Optional.of(test));
    }

    EffectiveToolchains composeWorkspaceMember(
            AuthoredToolchains root,
            AuthoredToolchains member,
            EffectiveProjectIdentity identity,
            String rootManifestPath,
            String memberManifestPath,
            boolean bom) {
        if (member.zolt().isPresent()) {
            throw new IllegalArgumentException(
                    "A workspace member cannot declare [toolchain.zolt]; the workspace root pin is authoritative.");
        }
        Optional<EffectiveValue<ZoltVersionPin>> zolt = root.zolt().map(value ->
                EffectiveValue.inherited(
                        value, source(rootManifestPath, "toolchain", "zolt", "version")));
        if (bom) {
            return EffectiveToolchains.withoutJava(zolt);
        }

        EffectiveValue<JavaFeatureRelease> projectRelease = identity.javaRelease().orElseThrow();
        EffectiveJavaRuntime main = member.mainJava()
                .<EffectiveJavaRuntime>map(value -> requestedMain(
                        value, projectRelease, memberManifestPath, false))
                .orElseGet(() -> root.mainJava()
                        .<EffectiveJavaRuntime>map(value -> requestedMain(
                                value, projectRelease, rootManifestPath, true))
                        .orElseGet(() -> new EffectiveJavaRuntime.System(projectRelease)));
        EffectiveTestJavaRuntime test = member.testJava()
                .<EffectiveTestJavaRuntime>map(value -> requestedTest(
                        value, main, projectRelease, memberManifestPath, false))
                .orElseGet(() -> root.testJava()
                        .<EffectiveTestJavaRuntime>map(value -> requestedTest(
                                value, main, projectRelease, rootManifestPath, true))
                        .orElseGet(() -> new EffectiveTestJavaRuntime.SameAsMain(main)));
        return new EffectiveToolchains(zolt, Optional.of(main), Optional.of(test));
    }

    private static EffectiveJavaRuntime.Requested requestedMain(
            AuthoredJavaToolchain authored,
            EffectiveValue<JavaFeatureRelease> projectRelease,
            String manifestPath) {
        return requestedMain(authored, projectRelease, manifestPath, false);
    }

    private static EffectiveJavaRuntime.Requested requestedMain(
            AuthoredJavaToolchain authored,
            EffectiveValue<JavaFeatureRelease> projectRelease,
            String manifestPath,
            boolean inherited) {
        boolean onlyDefaultVersion = authored.version()
                .filter(projectRelease.value()::equals)
                .isPresent()
                && authored.distribution().isEmpty()
                && authored.features().map(Set::isEmpty).orElse(true)
                && authored.policy().isEmpty();
        if (onlyDefaultVersion) {
            throw new IllegalArgumentException(
                    "An authored [toolchain.java] table must contain a distribution, feature, "
                            + "policy, or nondefault version.");
        }
        return new EffectiveJavaRuntime.Requested(
                authored.version()
                        .map(value -> sourced(
                                value, manifestPath, inherited,
                                "toolchain", "java", "version"))
                        .orElse(projectRelease),
                authored.distribution()
                        .map(value -> sourced(
                                value, manifestPath, inherited,
                                "toolchain", "java", "distribution"))
                        .orElseGet(() -> EffectiveValue.builtIn(JavaDistribution.TEMURIN)),
                authored.features()
                        .map(value -> sourced(
                                value, manifestPath, inherited,
                                "toolchain", "java", "features"))
                        .orElseGet(() -> EffectiveValue.builtIn(Set.<JavaFeature>of())),
                authored.policy()
                        .map(value -> sourced(
                                value, manifestPath, inherited,
                                "toolchain", "java", "policy"))
                        .orElseGet(() -> EffectiveValue.builtIn(ToolchainPolicy.PREFER_MANAGED)));
    }

    private static EffectiveTestJavaRuntime.Requested requestedTest(
            AuthoredJavaTestToolchain authored,
            EffectiveJavaRuntime main,
            EffectiveValue<JavaFeatureRelease> projectRelease,
            String manifestPath) {
        return requestedTest(authored, main, projectRelease, manifestPath, false);
    }

    private static EffectiveTestJavaRuntime.Requested requestedTest(
            AuthoredJavaTestToolchain authored,
            EffectiveJavaRuntime main,
            EffectiveValue<JavaFeatureRelease> projectRelease,
            String manifestPath,
            boolean inherited) {
        return new EffectiveTestJavaRuntime.Requested(
                authored.version()
                        .map(value -> sourced(
                                value, manifestPath, inherited,
                                "toolchain", "java", "test", "version"))
                        .orElseGet(() -> mainVersion(main, projectRelease)),
                authored.distribution()
                        .map(value -> sourced(
                                value, manifestPath, inherited,
                                "toolchain", "java", "test", "distribution"))
                        .orElseGet(() -> mainDistribution(main)),
                authored.policy()
                        .map(value -> sourced(
                                value, manifestPath, inherited,
                                "toolchain", "java", "test", "policy"))
                        .orElseGet(() -> mainPolicy(main)));
    }

    private static <T> EffectiveValue<T> sourced(
            T value,
            String manifestPath,
            boolean inherited,
            String... path) {
        ManifestSource source = source(manifestPath, path);
        return inherited
                ? EffectiveValue.inherited(value, source)
                : EffectiveValue.authored(value, source);
    }

    private static EffectiveValue<JavaFeatureRelease> mainVersion(
            EffectiveJavaRuntime main,
            EffectiveValue<JavaFeatureRelease> projectRelease) {
        return main instanceof EffectiveJavaRuntime.Requested requested
                ? requested.version()
                : projectRelease;
    }

    private static EffectiveValue<JavaDistribution> mainDistribution(EffectiveJavaRuntime main) {
        return main instanceof EffectiveJavaRuntime.Requested requested
                ? requested.distribution()
                : EffectiveValue.builtIn(JavaDistribution.TEMURIN);
    }

    private static EffectiveValue<ToolchainPolicy> mainPolicy(EffectiveJavaRuntime main) {
        return main instanceof EffectiveJavaRuntime.Requested requested
                ? requested.policy()
                : EffectiveValue.builtIn(ToolchainPolicy.PREFER_MANAGED);
    }

    private static ManifestSource source(String manifestPath, String... path) {
        return new ManifestSource(manifestPath, List.of(path));
    }
}
