package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.RunPackageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.PackageMode;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class WorkspaceRunPackageLaunchPolicyTest {
    private final WorkspaceRunPackageService service =
            new WorkspaceRunPackageService();

    @TempDir
    private Path tempDir;

    @ParameterizedTest
    @EnumSource(
            value = PackageMode.class,
            names = {"SPRING_BOOT", "SPRING_BOOT_WAR", "UBER"})
    void selfContainedWorkspaceModesLaunchOnlyTheirPackagedArchive(
            PackageMode mode) throws IOException {
        workspace("""
                [workspace]
                name = "launch-policy"
                members = ["apps/api"]
                """);
        member("apps/api", "api");
        Path cache = tempDir.resolve("launch-policy-cache");
        WorkspaceBuildPlan plan = service.planRunPackages(
                WorkspacePlanTarget.at(tempDir),
                cache,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));
        Path missingRuntime =
                tempDir.resolve("deliberately-missing.jar");
        Classpath empty = new Classpath(List.of());
        ClasspathSet classpaths = new ClasspathSet(
                empty,
                new Classpath(List.of(missingRuntime)),
                empty,
                empty,
                empty,
                empty,
                empty);
        BuildResult buildResult = new BuildResult(
                Optional.empty(),
                1,
                0,
                tempDir.resolve("apps/api/target/classes"),
                "");
        WorkspaceBuildResult.MemberBuildResult memberBuild =
                new WorkspaceBuildResult.MemberBuildResult(
                        "apps/api",
                        buildResult,
                        classpaths,
                        List.of());
        Path archive = tempDir.resolve(
                "apps/api/target/api-0.1.0"
                        + (mode == PackageMode.SPRING_BOOT_WAR
                                ? ".war"
                                : ".jar"));
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "packaged application");
        PackageResult packageResult = new PackageResult(
                buildResult,
                mode,
                archive,
                Optional.empty(),
                1,
                true);
        WorkspacePackageResult packaged = new WorkspacePackageResult(
                Optional.empty(),
                List.of(memberBuild),
                List.of(new WorkspacePackageResult.MemberPackageResult(
                        "apps/api",
                        packageResult)));
        List<List<String>> commands = new java.util.ArrayList<>();
        WorkspaceRunPackageService launchService =
                new WorkspaceRunPackageService(
                        new WorkspacePackageService(),
                        new TestJdkChecker(),
                        new JavaRunner(
                                java.io.File.pathSeparator,
                                (command, output) -> {
                                    commands.add(command);
                                    return new JavaRunner.ProcessResult(0, "ok\n");
                                }));

        launchService.runPackagedMembers(plan, packaged, List.of("argument"));

        assertEquals(1, commands.size());
        assertEquals(
                Path.of(System.getProperty("java.home"))
                        .resolve("bin")
                        .resolve(executable("java"))
                        .toString(),
                commands.getFirst().get(0));
        assertEquals("-jar", commands.getFirst().get(1));
        assertTrue(commands.getFirst().get(2).contains(
                ".zolt" + java.io.File.separator + "run"));
        assertEquals("argument", commands.getFirst().get(3));
        assertTrue(commands.getFirst().stream().noneMatch(
                value -> value.contains("deliberately-missing")));
    }

    @Test
    void workspaceQuarkusRunPackageRejectsBeforeJavaLaunch()
            throws IOException {
        workspace("""
                [workspace]
                name = "quarkus-launch-policy"
                members = ["apps/api"]
                """);
        member("apps/api", "api");
        WorkspaceBuildPlan plan = service.planRunPackages(
                WorkspacePlanTarget.at(tempDir),
                tempDir.resolve("quarkus-launch-cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")));
        Classpath empty = new Classpath(List.of());
        ClasspathSet classpaths = new ClasspathSet(
                empty,
                empty,
                empty,
                empty,
                empty,
                empty,
                empty);
        BuildResult buildResult = new BuildResult(
                Optional.empty(),
                1,
                0,
                tempDir.resolve("apps/api/target/classes"),
                "");
        WorkspaceBuildResult.MemberBuildResult memberBuild =
                new WorkspaceBuildResult.MemberBuildResult(
                        "apps/api",
                        buildResult,
                        classpaths,
                        List.of());
        PackageResult packageResult = new PackageResult(
                buildResult,
                PackageMode.QUARKUS,
                tempDir.resolve(
                        "apps/api/target/quarkus-app/quarkus-run.jar"),
                Optional.empty(),
                1,
                true);
        WorkspacePackageResult packaged = new WorkspacePackageResult(
                Optional.empty(),
                List.of(memberBuild),
                List.of(new WorkspacePackageResult.MemberPackageResult(
                        "apps/api",
                        packageResult)));

        RunPackageException exception = assertThrows(
                RunPackageException.class,
                () -> service.runPackagedMembers(plan, packaged, List.of()));

        assertTrue(exception.getMessage().contains("Use `zolt run`"));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    private void member(String path, String name) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"
                main = "com.acme.api.Api"
                """.formatted(name, currentJavaMajorVersion()));
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }

    private static String executable(String name) {
        return System.getProperty("os.name")
                        .toLowerCase(Locale.ROOT)
                        .contains("win")
                ? name + ".exe"
                : name;
    }

    private static final class TestJdkChecker
            implements sh.zolt.doctor.JdkChecker {
        @Override
        public sh.zolt.doctor.JdkStatus detect(String requiredVersion) {
            Path javaHome = Path.of(System.getProperty("java.home"));
            return new sh.zolt.doctor.JdkStatus(
                    Optional.of(javaHome),
                    Optional.of(javaHome.resolve("bin").resolve(executable("java"))),
                    Optional.of(javaHome.resolve("bin").resolve(executable("javac"))),
                    Optional.of(javaHome.resolve("bin").resolve(executable("jar"))),
                    Optional.of(requiredVersion),
                    requiredVersion);
        }
    }
}
