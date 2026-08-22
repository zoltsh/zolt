package sh.zolt.cli.command.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport;
import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.doctor.SelfHostingCheckService;
import sh.zolt.toolchain.JavaToolchainStatusService;
import sh.zolt.toolchain.jvm.JavaRuntimeInfo;
import sh.zolt.toolchain.jvm.JavaToolchainProbe;
import sh.zolt.toolchain.jvm.JavaToolchainSource;
import sh.zolt.toolchain.jvm.ResolvedJavaToolchain;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The failure half of doctor's environment mode. Every host that runs the suite has a working JDK, so
 * the unhealthy path is only reachable by injecting a toolchain status service that reports one — which
 * is what {@link DoctorCommand}'s package-private constructor exists for.
 */
final class DoctorCommandEnvironmentFailureTest {
    @TempDir
    private Path tempDir;

    @Test
    void doctorFailsWhenTheEnvironmentToolchainCannotResolve() throws IOException {
        Path emptyDir = tempDir.resolve("broken-environment");
        Files.createDirectories(emptyDir);

        CommandResult result = CliTestSupport.executeCommand(
                doctor(unresolvableJava()),
                "--directory",
                emptyDir.toString());

        assertEquals(1, result.exitCode(), result.stdout() + result.stderr());
        assertTrue(result.stdout().contains("JDK status: error"), result.stdout());
        assertTrue(result.stdout().contains("java: missing"), result.stdout());
        assertTrue(result.stderr().contains("error: Missing `java`."), result.stderr());
        assertTrue(result.stderr().contains("error: Environment health check failed."), result.stderr());
    }

    /** An unhealthy environment still reports the machine facts doctor could gather. */
    @Test
    void doctorStillReportsTheRestOfTheEnvironmentWhenTheToolchainIsBroken() throws IOException {
        Path emptyDir = tempDir.resolve("partly-broken-environment");
        Files.createDirectories(emptyDir);

        CommandResult result = CliTestSupport.executeCommand(
                doctor(unresolvableJava()),
                "--directory",
                emptyDir.toString());

        assertTrue(result.stdout().contains("Zolt: ok"), result.stdout());
        assertTrue(result.stdout().contains("Zolt home: ok"), result.stdout());
        assertTrue(result.stdout().contains("skip Not a Zolt project: no zolt.toml in "), result.stdout());
    }

    private static DoctorCommand doctor(JavaToolchainProbe probe) {
        return new DoctorCommand(
                new ManifestProjectLoader(),
                new SelfHostingCheckService(),
                new JavaToolchainStatusService(probe));
    }

    private static JavaToolchainProbe unresolvableJava() {
        return request -> new ResolvedJavaToolchain(
                JavaToolchainSource.AMBIENT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                JavaRuntimeInfo.empty(),
                request,
                List.of("Missing `java`. Install a JDK, set JAVA_HOME, or configure [toolchain.java]."),
                List.of());
    }
}
