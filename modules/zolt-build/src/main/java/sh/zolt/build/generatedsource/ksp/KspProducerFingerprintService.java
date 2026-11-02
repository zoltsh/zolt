package sh.zolt.build.generatedsource.ksp;

import java.util.List;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;

/** Produces the checksum-derived KSP engine and processor identity used by every reuse path. */
public final class KspProducerFingerprintService {
    private static final String SEMANTICS = "zolt.ksp-producer.v1";

    private final ToolchainResolver toolchainResolver;

    public KspProducerFingerprintService() {
        this(new KspJvmToolchainResolver()::resolve);
    }

    KspProducerFingerprintService(ToolchainResolver toolchainResolver) {
        this.toolchainResolver = Objects.requireNonNull(
                toolchainResolver,
                "KSP toolchain resolver is required.");
    }

    public String fingerprint(
            List<ResolvedClasspathPackage> packages,
            String kotlinVersion,
            GeneratedSourceStep step) {
        GeneratedSourceStep generationStep = Objects.requireNonNull(
                step,
                "KSP generated source step is required.");
        if (generationStep.kind() != GeneratedSourceKind.KSP) {
            throw new BuildException(
                    "KSP producer fingerprint requires a KSP generated source step.");
        }
        KspGenerationSettings settings = generationStep.ksp();
        KspJvmToolchain toolchain = toolchainResolver.resolve(
                packages == null ? List.of() : List.copyOf(packages),
                settings.engineGroup(),
                settings.processorGroup(),
                kotlinVersion,
                settings.version().orElseThrow());
        return SEMANTICS + "|" + toolchain.identity();
    }

    @FunctionalInterface
    interface ToolchainResolver {
        KspJvmToolchain resolve(
                List<ResolvedClasspathPackage> packages,
                String engineGroup,
                String processorGroup,
                String kotlinVersion,
                String kspVersion);
    }
}
