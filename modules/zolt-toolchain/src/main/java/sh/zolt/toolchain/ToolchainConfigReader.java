package sh.zolt.toolchain;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import sh.zolt.error.ActionableError;
import sh.zolt.manifest.authored.AuthoredJavaTestToolchain;
import sh.zolt.manifest.authored.AuthoredJavaToolchain;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.effective.EffectiveJavaRuntime;
import sh.zolt.manifest.effective.EffectiveTestJavaRuntime;
import sh.zolt.manifest.effective.EffectiveToolchains;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/**
 * Reads the {@code [toolchain.java]} and {@code [toolchain.java.test]} requests from a manifest
 * written in the final language.
 *
 * <p>For a project manifest the request comes from effective composition, which already applies the
 * defaults of design §11.3 and §11.4: an authored request becomes
 * {@link EffectiveJavaRuntime.Requested} with the project release filled in when {@code version} is
 * omitted, and a manifest without a request composes to {@link EffectiveJavaRuntime.System}, which
 * this reader reports as "no toolchain request" so callers keep using the ambient JDK.
 *
 * <p>A virtual workspace root carries {@code [toolchain.java]} as shared configuration but has no
 * {@code [project]} to compose against, so its request is read exactly as authored.
 */
public final class ToolchainConfigReader {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    public Optional<JavaToolchainRequest> readJava(Path configPath) {
        return readJava(read(configPath));
    }

    public Optional<JavaToolchainRequest> readJava(String content) {
        AuthoredManifest authored = loader.document(content).authored();
        if (authored.project().isEmpty()) {
            return authored.toolchains().mainJava().flatMap(ToolchainConfigReader::authoredJava);
        }
        return toolchains(content).mainJava().flatMap(runtime -> switch (runtime) {
            case EffectiveJavaRuntime.System ignored -> Optional.empty();
            case EffectiveJavaRuntime.Requested requested -> Optional.of(new JavaToolchainRequest(
                    requested.version().value().toString(),
                    Optional.of(requested.distribution().value()),
                    requested.features().value(),
                    requested.policy().value()));
        });
    }

    /**
     * Reads the optional {@code [toolchain.java.test]} scoped runtime toolchain, which pins the JDK
     * used to run tests (compile stays on {@code [toolchain.java]}). Distribution and policy default
     * from the main entry while Java features deliberately do not inherit; returns empty when no
     * separate test runtime toolchain is declared.
     */
    public Optional<JavaToolchainRequest> readJavaTest(Path configPath) {
        return readJavaTest(read(configPath));
    }

    public Optional<JavaToolchainRequest> readJavaTest(String content) {
        AuthoredManifest authored = loader.document(content).authored();
        if (authored.project().isEmpty()) {
            return authored.toolchains().testJava().flatMap(ToolchainConfigReader::authoredJavaTest);
        }
        return toolchains(content).testJava().flatMap(runtime -> switch (runtime) {
            case EffectiveTestJavaRuntime.SameAsMain ignored -> Optional.empty();
            case EffectiveTestJavaRuntime.Requested requested -> Optional.of(new JavaToolchainRequest(
                    requested.version().value().toString(),
                    Optional.of(requested.distribution().value()),
                    Set.of(),
                    requested.policy().value()));
        });
    }

    private EffectiveToolchains toolchains(String content) {
        return loader.effective(content).project().shared().toolchains();
    }

    private static Optional<JavaToolchainRequest> authoredJava(AuthoredJavaToolchain toolchain) {
        return toolchain.version().map(version -> new JavaToolchainRequest(
                version.toString(),
                toolchain.distribution(),
                toolchain.features().orElseGet(Set::of),
                toolchain.policy().orElse(null)));
    }

    private static Optional<JavaToolchainRequest> authoredJavaTest(AuthoredJavaTestToolchain toolchain) {
        return toolchain.version().map(version -> new JavaToolchainRequest(
                version.toString(),
                toolchain.distribution(),
                Set.of(),
                toolchain.policy().orElse(null)));
    }

    private static String read(Path configPath) {
        Path normalized = configPath.toAbsolutePath().normalize();
        try {
            return Files.readString(normalized);
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not read zolt.toml at " + normalized + ".",
                    "Check that the file exists and is readable.",
                    exception));
        }
    }
}
