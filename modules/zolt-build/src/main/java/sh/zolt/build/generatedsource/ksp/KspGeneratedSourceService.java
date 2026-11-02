package sh.zolt.build.generatedsource.ksp;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;

/** Executes one isolated, non-incremental KSP2 step and atomically publishes its owned outputs. */
public final class KspGeneratedSourceService {
    private final ToolchainResolver toolchainResolver;
    private final KspJvmCommandBuilder commandBuilder;
    private final KspJvmProcess process;

    public KspGeneratedSourceService() {
        this(
                new KspJvmToolchainResolver()::resolve,
                new KspJvmCommandBuilder(File.pathSeparator),
                new KspJvmProcess());
    }

    KspGeneratedSourceService(
            ToolchainResolver toolchainResolver,
            KspJvmCommandBuilder commandBuilder,
            KspJvmProcess process) {
        this.toolchainResolver = Objects.requireNonNull(
                toolchainResolver,
                "KSP toolchain resolver is required.");
        this.commandBuilder = Objects.requireNonNull(
                commandBuilder,
                "KSP command builder is required.");
        this.process = Objects.requireNonNull(process, "KSP process is required.");
    }

    public void generate(
            Path projectRoot,
            String scope,
            GeneratedSourceStep step,
            List<ResolvedClasspathPackage> packages,
            String kotlinVersion,
            JdkStatus jdkStatus,
            KotlinCompilerOptions compilerOptions,
            List<Path> kotlinSourceRoots,
            List<Path> javaSourceRoots,
            List<Path> libraries) {
        Path root = Objects.requireNonNull(projectRoot, "Project root is required.")
                .toAbsolutePath()
                .normalize();
        GeneratedSourceStep generationStep = Objects.requireNonNull(
                step,
                "KSP generated source step is required.");
        KspGeneratedSourceValidator.validate(root, scope, generationStep);
        KspGenerationSettings settings = generationStep.ksp();
        String kspVersion = settings.version().orElseThrow();
        KspJvmToolchain toolchain = toolchainResolver.resolve(
                packages == null ? List.of() : List.copyOf(packages),
                settings.engineGroup(),
                settings.processorGroup(),
                kotlinVersion,
                kspVersion);
        String subject = "[generated." + scope + "." + generationStep.id() + "]";
        try (KspOutputTransaction transaction = KspOutputTransaction.begin(
                root,
                scope,
                generationStep)) {
            KspJvmInvocation invocation = KspJvmInvocationFactory.create(
                    root,
                    transaction.staging(),
                    jdkStatus,
                    toolchain,
                    compilerOptions,
                    kotlinSourceRoots,
                    javaSourceRoots,
                    libraries,
                    settings);
            process.run(commandBuilder.command(invocation), root, subject);
            transaction.commit();
        }
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
