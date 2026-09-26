package sh.zolt.toolchain.jvm;

import sh.zolt.project.toolchain.JavaToolchainRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record ResolvedJavaToolchain(
        JavaToolchainSource source,
        Optional<Path> javaHome,
        Optional<Path> java,
        Optional<Path> javac,
        Optional<Path> jar,
        Optional<Path> nativeImage,
        JavaRuntimeInfo runtime,
        Optional<String> artifactSha256,
        JavaToolchainRequest request,
        List<String> problems,
        List<String> notes) {
    public ResolvedJavaToolchain {
        javaHome = javaHome == null ? Optional.empty() : javaHome;
        java = java == null ? Optional.empty() : java;
        javac = javac == null ? Optional.empty() : javac;
        jar = jar == null ? Optional.empty() : jar;
        nativeImage = nativeImage == null ? Optional.empty() : nativeImage;
        runtime = runtime == null ? JavaRuntimeInfo.empty() : runtime;
        artifactSha256 = artifactSha256 == null ? Optional.empty() : artifactSha256;
        problems = problems == null ? List.of() : List.copyOf(problems);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public ResolvedJavaToolchain(
            JavaToolchainSource source,
            Optional<Path> javaHome,
            Optional<Path> java,
            Optional<Path> javac,
            Optional<Path> jar,
            Optional<Path> nativeImage,
            JavaRuntimeInfo runtime,
            JavaToolchainRequest request,
            List<String> problems,
            List<String> notes) {
        this(
                source,
                javaHome,
                java,
                javac,
                jar,
                nativeImage,
                runtime,
                Optional.empty(),
                request,
                problems,
                notes);
    }

    public boolean ok() {
        return problems.isEmpty();
    }
}
