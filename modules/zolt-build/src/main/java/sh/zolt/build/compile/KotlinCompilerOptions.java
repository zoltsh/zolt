package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.compile.kotlin.KotlinCompilerInvocationContext;
import sh.zolt.build.compile.kotlin.KotlinCompilerPolicy;
import sh.zolt.build.compile.kotlin.KotlinJvmTargetOptions;
import sh.zolt.project.CompilerSettings;

/** Immutable compiler policy plus the invocation-local context for one Kotlin/JVM compile. */
public record KotlinCompilerOptions(
        KotlinJvmTargetOptions target,
        KotlinCompilerPolicy policy,
        KotlinCompilerInvocationContext invocation) {
    public KotlinCompilerOptions {
        target = Objects.requireNonNull(target, "Kotlin JVM target options are required.");
        policy = Objects.requireNonNull(policy, "Kotlin compiler policy is required.");
        invocation = Objects.requireNonNull(invocation, "Kotlin compiler invocation context is required.");
    }

    public KotlinCompilerOptions(
            KotlinJvmTargetOptions target,
            KotlinCompilerPolicy policy) {
        this(target, policy, KotlinCompilerInvocationContext.none());
    }

    public static KotlinCompilerOptions defaults(
            String release,
            String moduleName,
            boolean hostPlatformApi) {
        return defaults(release, moduleName, hostPlatformApi, !hostPlatformApi);
    }

    public static KotlinCompilerOptions defaults(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease) {
        return forArguments(
                release,
                moduleName,
                hostPlatformApi,
                useJdkRelease,
                List.of(),
                KotlinCompilationScope.MAIN);
    }

    public static KotlinCompilerOptions forArguments(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            List<String> arguments,
            KotlinCompilationScope scope) {
        KotlinCompilationScope compilationScope = Objects.requireNonNull(
                scope,
                "Kotlin compilation scope is required.");
        List<String> normalizedArguments = List.copyOf(Objects.requireNonNull(
                arguments,
                "Kotlin compiler arguments are required."));
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "",
                "",
                compilationScope == KotlinCompilationScope.MAIN ? normalizedArguments : List.of(),
                compilationScope == KotlinCompilationScope.TEST ? normalizedArguments : List.of());
        return new KotlinCompilerOptions(
                new KotlinJvmTargetOptions(
                        release,
                        moduleName,
                        hostPlatformApi,
                        useJdkRelease),
                KotlinCompilerArgumentPolicy.map(compiler, compilationScope));
    }

    public String release() {
        return target.release();
    }

    public String moduleName() {
        return target.moduleName();
    }

    public boolean hostPlatformApi() {
        return target.hostPlatformApi();
    }

    public boolean useJdkRelease() {
        return target.useJdkRelease();
    }

    public Path friendPath() {
        return invocation.friendPath();
    }

    public KotlinCompilerOptions withFriendPath(Path path) {
        return new KotlinCompilerOptions(target, policy, invocation.withFriendPath(path));
    }
}
