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
import sh.zolt.project.toolchain.JavaFeatureRelease;
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
 * {@code [project]} to compose against, so its request is read as authored. An omitted {@code version}
 * there still means the shared project release (design §11.3), which the root declares under
 * {@code [workspace.project]}, so the release is taken from there rather than dropping the request.
 */
public final class ToolchainConfigReader {
    private final ManifestProjectConfigLoader loader = new ManifestProjectConfigLoader();

    public Optional<JavaToolchainRequest> readJava(Path configPath) {
        return readJava(read(configPath));
    }

    public Optional<JavaToolchainRequest> readJava(String content) {
        AuthoredManifest authored = loader.document(content).authored();
        if (authored.project().isEmpty()) {
            return authored.toolchains().mainJava()
                    .flatMap(toolchain -> authoredJava(toolchain, sharedRelease(authored)));
        }
        return toolchains(content).mainJava().flatMap(ToolchainConfigReader::mainRequest);
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
            return authored.toolchains().testJava()
                    .flatMap(toolchain -> authoredJavaTest(toolchain, sharedRelease(authored)));
        }
        return toolchains(content).testJava().flatMap(ToolchainConfigReader::testRequest);
    }

    /**
     * Reads one workspace member's effective Java toolchains (design §4.5 "Java toolchains"). The
     * member inherits the root {@code [toolchain.java]} and {@code [toolchain.java.test]} whole when
     * it declares none of its own, and the {@code version} default is the member's effective project
     * release, which may itself come from {@code [workspace.project]} (design §4.3, §11.3). A member
     * is never composed standalone: it may legally spell {@code workspace = true}, reference a
     * root-owned credential, or omit an inherited identity field.
     */
    public MemberToolchains readWorkspaceMember(
            String rootContent,
            String memberContent,
            String memberPath) {
        EffectiveToolchains effective = loader
                .effectiveWorkspaceMember(rootContent, memberContent, memberPath)
                .project()
                .shared()
                .toolchains();
        boolean authoredByMember = loader.document(memberContent)
                .authored()
                .toolchains()
                .mainJava()
                .isPresent();
        Optional<JavaToolchainRequest> main = effective.mainJava().flatMap(ToolchainConfigReader::mainRequest);
        return new MemberToolchains(
                main,
                main.isPresent() && !authoredByMember,
                effective.testJava().flatMap(ToolchainConfigReader::testRequest));
    }

    /** One workspace member's effective toolchain requests and where the main request was authored. */
    public record MemberToolchains(
            Optional<JavaToolchainRequest> main,
            boolean mainInherited,
            Optional<JavaToolchainRequest> test) {
    }

    private EffectiveToolchains toolchains(String content) {
        return loader.effective(content).project().shared().toolchains();
    }

    private static Optional<JavaToolchainRequest> mainRequest(EffectiveJavaRuntime runtime) {
        return switch (runtime) {
            case EffectiveJavaRuntime.System ignored -> Optional.empty();
            case EffectiveJavaRuntime.Requested requested -> Optional.of(new JavaToolchainRequest(
                    requested.version().value().toString(),
                    Optional.of(requested.distribution().value()),
                    requested.features().value(),
                    requested.policy().value()));
        };
    }

    private static Optional<JavaToolchainRequest> testRequest(EffectiveTestJavaRuntime runtime) {
        return switch (runtime) {
            case EffectiveTestJavaRuntime.SameAsMain ignored -> Optional.empty();
            case EffectiveTestJavaRuntime.Requested requested -> Optional.of(new JavaToolchainRequest(
                    requested.version().value().toString(),
                    Optional.of(requested.distribution().value()),
                    Set.of(),
                    requested.policy().value()));
        };
    }

    private static Optional<JavaToolchainRequest> authoredJava(
            AuthoredJavaToolchain toolchain, Optional<String> sharedRelease) {
        return toolchain.version()
                .map(JavaFeatureRelease::toString)
                .or(() -> sharedRelease)
                .map(version -> new JavaToolchainRequest(
                        version,
                        toolchain.distribution(),
                        toolchain.features().orElseGet(Set::of),
                        toolchain.policy().orElse(null)));
    }

    private static Optional<JavaToolchainRequest> authoredJavaTest(
            AuthoredJavaTestToolchain toolchain, Optional<String> sharedRelease) {
        return toolchain.version()
                .map(JavaFeatureRelease::toString)
                .or(() -> sharedRelease)
                .map(version -> new JavaToolchainRequest(
                        version,
                        toolchain.distribution(),
                        Set.of(),
                        toolchain.policy().orElse(null)));
    }

    /** The Java release a virtual workspace root shares with its members (design §4.3, §11.3). */
    private static Optional<String> sharedRelease(AuthoredManifest authored) {
        return authored.workspace()
                .flatMap(workspace -> workspace.projectDefaults())
                .flatMap(defaults -> defaults.javaRelease())
                .map(JavaFeatureRelease::toString);
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
