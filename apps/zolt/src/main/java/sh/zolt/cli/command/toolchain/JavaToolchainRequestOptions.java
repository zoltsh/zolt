package sh.zolt.cli.command.toolchain;

import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaFeatureVersion;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.util.Set;

final class JavaToolchainRequestOptions {
    private JavaToolchainRequestOptions() {
    }

    static JavaToolchainRequest javaRequest(
            String version,
            boolean temurin,
            boolean graalvm,
            boolean nativeImage,
            String policy,
            String scope,
            String policyScope) {
        String featureVersion = requireFeatureVersion(version, scope);
        if (temurin && graalvm) {
            throw new ActionableException(
                    "Choose one Java distribution for " + scope + ".",
                    "Use either --temurin or --graalvm, not both.");
        }
        if (temurin && nativeImage) {
            throw new ActionableException(
                    "Temurin does not provide native-image.",
                    "Use --graalvm --native-image for a Native Image-capable Java.");
        }
        JavaDistribution distribution = graalvm || nativeImage
                ? JavaDistribution.GRAALVM_COMMUNITY
                : JavaDistribution.TEMURIN;
        ToolchainPolicy parsedPolicy = ToolchainPolicy.fromId(policy).orElseThrow(() -> new ActionableException(
                "Unsupported " + policyScope + " policy `" + policy + "`.",
                "Use one of: " + ToolchainPolicy.supportedIds() + "."));
        Set<JavaFeature> features = nativeImage ? Set.of(JavaFeature.NATIVE_IMAGE) : Set.of();
        return new JavaToolchainRequest(featureVersion, distribution, features, parsedPolicy);
    }

    private static String requireFeatureVersion(String version, String scope) {
        String normalized = version == null ? "" : version.strip();
        if (!JavaFeatureVersion.isConcrete(normalized)) {
            throw new ActionableException(
                    "Java version `" + normalized + "` is not a concrete feature release for " + scope + ".",
                    "Use a feature release number such as 21 or 25; aliases such as `latest` are not supported.");
        }
        return normalized;
    }
}
