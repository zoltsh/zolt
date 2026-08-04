package sh.zolt.cli.publish;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReleaseCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void releaseArchiveAssemblesArchiveFromNativeBinary() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(projectDir.resolve("README.md"), "# Demo\n");
        Path binary = projectDir.resolve("target/native/demo");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "native");
        CommandResult result = execute(
                "--progress=always",
                "release-archive",
                "--directory", projectDir.toString(),
                "--target", "linux-x64");
        Path archive = projectDir.resolve("dist/demo-0.1.0-linux-x64.tar.gz");
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Assembled linux-x64 release archive"));
        assertTrue(result.stdout().contains("4 files"));
        assertTrue(result.stdout().contains("root demo-0.1.0-linux-x64"));
        assertTrue(result.stdout().contains("→ wrote " + archive));
        assertTrue(result.stdout().contains("→ wrote " + archive + ".sha256"));
        assertTrue(result.stdout().contains("→ wrote " + projectDir.resolve("dist/release-manifest.json")));
        assertTrue(result.stderr().contains("Assembling release archive..."));
        assertTrue(result.stderr().contains("Assembled linux-x64 release archive"));
        assertTrue(Files.exists(archive));
        assertTrue(Files.exists(projectDir.resolve("dist/demo-0.1.0-linux-x64.tar.gz.sha256")));
        assertTrue(Files.exists(projectDir.resolve("dist/release-manifest.json")));
    }

    @Test
    void releaseArchiveUsesModernHumanOutputControls() throws IOException {
        Path projectDir = tempDir.resolve("archive-output");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Path binary = projectDir.resolve("target/native/demo");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "native");
        CommandResult color = execute(
                "--color=always",
                "--progress=always",
                "release-archive",
                "--directory", projectDir.toString(),
                "--target", "linux-x64",
                "--output", "dist-color");
        CommandResult quiet = execute(
                "--quiet",
                "release-archive",
                "--directory", projectDir.toString(),
                "--target", "linux-x64",
                "--output", "dist-quiet");
        Path archive = projectDir.resolve("dist-color/demo-0.1.0-linux-x64.tar.gz");
        assertEquals(0, color.exitCode(), color.stderr());
        assertTrue(color.stdout().contains("\u001B[32m✔\u001B[0m Assembled linux-x64 release archive"));
        assertFalse(color.stdout().contains("\u001B[32mAssembled linux-x64 release archive\u001B[0m"));
        assertTrue(color.stdout().contains("3 files\u001B[0m"));
        assertTrue(color.stdout().contains("root demo-0.1.0-linux-x64\u001B[0m"));
        assertTrue(color.stdout().contains("\u001B[36m→\u001B[0m wrote \u001B[36m" + archive + "\u001B[0m"));
        assertFalse(color.stdout().contains("\u001B[32mWrote archive to "));
        assertTrue(color.stdout().contains("\u001B[36m→\u001B[0m wrote \u001B[36m" + archive + ".sha256\u001B[0m"));
        assertFalse(color.stdout().contains("\u001B[32mWrote checksum to "));
        assertTrue(color.stdout().contains("\u001B[36m→\u001B[0m wrote \u001B[36m"
                + projectDir.resolve("dist-color/release-manifest.json") + "\u001B[0m"));
        assertFalse(color.stdout().contains("\u001B[32mWrote manifest to "));
        assertTrue(color.stderr().contains("\u001B[36mAssembling\u001B[0m release archive..."));
        assertTrue(color.stderr().contains("\u001B[32mAssembled\u001B[0m linux-x64 release archive"));
        assertFalse(color.stderr().contains("\u001B[36mAssembling release archive...")
                || color.stderr().contains("\u001B[32mAssembled linux-x64 release archive"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
        assertTrue(Files.exists(projectDir.resolve("dist-quiet/demo-0.1.0-linux-x64.tar.gz")));
    }

    @Test
    void releaseArchiveReportsMissingBinaryClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");

        CommandResult result = execute(
                "release-archive",
                "--cwd", projectDir.toString(),
                "--target", "linux-x64");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Release archive requires native binary"));
        assertTrue(result.stderr().contains("Run `zolt native` or pass --binary <path>"));
    }

    @Test
    void releaseVerifyReportsMissingArchiveClearly() throws IOException {
        Path projectDir = tempDir.resolve("demo");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        CommandResult result = execute(
                "release-verify",
                "--cwd", projectDir.toString(),
                "dist/missing.tar.gz");
        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Release archive verification failed for"));
        assertTrue(result.stderr().contains("archive does not exist"));
        assertTrue(result.stderr().contains("Pass a valid release archive path"));
    }

    @Test
    void releaseVerifyUsesModernHumanOutputControls() throws IOException {
        Path projectDir = tempDir.resolve("verify-output");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        writeFakeZoltBinary(projectDir.resolve("target/native/zolt"));
        writeFakeJunitWorker(projectDir.resolve("target/libexec/zolt-junit-worker.jar"));
        writeFakeJunitWorker(projectDir.resolve("target/libexec/zolt-javac-worker.jar"));
        CommandResult archiveResult = execute(
                "release-archive",
                "--directory", projectDir.toString(),
                "--target", "linux-x64",
                "--binary", "target/native/zolt");
        Path archive = projectDir.resolve("dist/demo-0.1.0-linux-x64.tar.gz");
        assertEquals(0, archiveResult.exitCode(), archiveResult.stderr());
        CommandResult color = execute(
                "--color=always",
                "--progress=always",
                "release-verify",
                "--directory", projectDir.toString(),
                "--work-dir", "target/release-verify-color",
                "dist/demo-0.1.0-linux-x64.tar.gz");
        CommandResult quiet = execute(
                "--quiet",
                "release-verify",
                "--directory", projectDir.toString(),
                "--work-dir", "target/release-verify-quiet",
                "dist/demo-0.1.0-linux-x64.tar.gz");
        assertEquals(0, color.exitCode(), color.stderr());
        assertTrue(color.stdout().contains("Release verify status: \u001B[32mok\u001B[0m"));
        assertFalse(color.stdout().contains("\u001B[32mRelease\u001B[0m verify status"));
        assertTrue(color.stdout().contains("\u001B[32mok:\u001B[0m Verified release archive " + archive));
        assertFalse(color.stdout().contains("\u001B[32mok: Verified release archive"));
        assertTrue(color.stdout().contains("Unpacked to: "));
        assertFalse(color.stdout().contains("\u001B[32mUnpacked to "));
        assertTrue(color.stdout().contains("\u001B[32mok:\u001B[0m Ran smoke binary"));
        assertFalse(color.stdout().contains("\u001B[32mok: Ran smoke binary"));
        assertTrue(color.stdout().contains("Archives verified: 1"));
        assertFalse(color.stdout().contains("\u001B[32mArchives verified"));
        assertTrue(color.stderr().contains("\u001B[36mVerifying\u001B[0m release archives..."));
        assertTrue(color.stderr().contains("\u001B[32mVerified\u001B[0m 1 release archives"));
        assertFalse(color.stderr().contains("\u001B[36mVerifying release archives...")
                || color.stderr().contains("\u001B[32mVerified 1 release archives"));
        assertEquals(0, quiet.exitCode(), quiet.stderr());
        assertEquals("", quiet.stdout());
    }

    @Test
    void releaseVerifyDefaultsWorkDirectoryFromConfiguredOutputRoot() throws IOException {
        Path projectDir = tempDir.resolve("demo-output-root");
        writeProjectConfig(projectDir, "https://repo.maven.apache.org/maven2");
        Files.writeString(
                projectDir.resolve("zolt.toml"),
                Files.readString(projectDir.resolve("zolt.toml"))
                        .replace("[build]\n", "[build]\n                outputRoot = \".zolt/build\"\n"));
        CommandResult result = execute(
                "release-verify",
                "--directory", projectDir.toString(),
                "dist/missing.tar.gz");
        assertEquals(1, result.exitCode());
        assertTrue(Files.isDirectory(projectDir.resolve(".zolt/build/release-verify")));
        assertTrue(result.stderr().contains("archive does not exist"));
    }

    @Test
    void releaseIndexMergesCurrentChannelManifestWithPreviousIndex() throws IOException {
        Path channel = tempDir.resolve("zap-channel.json");
        Path previous = tempDir.resolve("zap-previous-index.json");
        Path output = tempDir.resolve("zap-index.json");
        Files.writeString(channel, channelManifest("0.1.0-zap.20260707.333333333333"));
        Files.writeString(previous, releaseIndex(
                releaseIndexVersion("0.1.0-zap.20260706.222222222222"),
                releaseIndexVersion("0.1.0-zap.20260705.111111111111")));

        CommandResult result = execute(
                "release-index",
                "--channel-manifest", channel.toString(),
                "--previous", previous.toString(),
                "--output", output.toString(),
                "--limit", "2");
        String index = Files.readString(output);

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("✔ Wrote zap release index"));
        assertTrue(result.stdout().contains("2 versions"));
        assertTrue(result.stdout().contains("latest 0.1.0-zap.20260707.333333333333"));
        assertTrue(result.stdout().contains("→ wrote " + output));
        assertTrue(index.contains("\"schemaVersion\": 1"));
        assertTrue(index.indexOf("0.1.0-zap.20260707.333333333333")
                < index.indexOf("0.1.0-zap.20260706.222222222222"));
        assertFalse(index.contains("0.1.0-zap.20260705.111111111111"));
    }

    private static void writeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"
                main = "com.example.Main"

                [repositories]
                test = "%s"

                [dependencies]

                [test.dependencies]

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    private static String channelManifest(String version) {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "zap",
                  "version": "%s",
                  "commit": "0123456789abcdef0123456789abcdef01234567",
                  "createdAt": "2026-07-07T00:00:00Z",
                  "artifacts": [
                    {
                      "target": "linux-x64",
                      "archive": "zolt-%s-linux-x64.tar.gz",
                      "archiveUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz",
                      "checksumUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz.sha256",
                      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "format": "tar.gz",
                      "binaryName": "zolt"
                    }
                  ]
                }
                """.formatted(version, version, version, version, version, version);
    }

    private static String releaseIndex(String... versions) {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "zap",
                  "updatedAt": "2026-07-06T20:00:00Z",
                  "versions": [
                %s
                  ]
                }
                """.formatted(String.join(",\n", versions).indent(4));
    }

    private static String releaseIndexVersion(String version) {
        return """
                {
                  "version": "%s",
                  "commit": "0123456789abcdef0123456789abcdef01234567",
                  "createdAt": "2026-07-06T20:00:00Z",
                  "artifacts": [
                    {
                      "target": "linux-x64",
                      "archive": "zolt-%s-linux-x64.tar.gz",
                      "archiveUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz",
                      "checksumUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz.sha256",
                      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                      "format": "tar.gz",
                      "binaryName": "zolt"
                    }
                  ]
                }
                """.formatted(version, version, version, version, version, version);
    }

    private static void writeFakeZoltBinary(Path binary) throws IOException {
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, """
                #!/usr/bin/env bash
                set -euo pipefail
                command="${1:-}"
                shift || true
                java_version="%s"

                option_value() {
                  local option="$1"
                  shift
                  while [[ "$#" -gt 0 ]]; do
                    if [[ "$1" == "$option" ]]; then
                      printf '%%s' "$2"
                      return 0
                    fi
                    shift
                  done
                }

                case "$command" in
                  --version)
                    printf '0.1.0\\n'
                    ;;
                  init)
                    cwd="$(option_value --cwd "$@")"
                    name="${@: -1}"
                    mkdir -p "$cwd/$name"
                    {
                      printf '[project]\\n'
                      printf 'name = "smoke"\\n'
                      printf 'version = "0.1.0"\\n'
                      printf 'group = "com.example"\\n'
                      printf 'java = "%%s"\\n' "$java_version"
                      printf '\\n[build]\\n'
                      printf 'source = "src/main/java"\\n'
                      printf 'test = "src/test/java"\\n'
                      printf 'output = "target/classes"\\n'
                      printf 'testOutput = "target/test-classes"\\n'
                    } > "$cwd/$name/zolt.toml"
                    ;;
                  build)
                    ;;
                  *)
                    printf 'unexpected command: %%s\\n' "$command" >&2
                    exit 2
                    ;;
                esac
                """.formatted(currentJavaMajorVersion()));
        assertTrue(binary.toFile().setExecutable(true));
    }

    private static void writeFakeJunitWorker(Path workerJar) throws IOException {
        Files.createDirectories(workerJar.getParent());
        Files.writeString(workerJar, "worker\n");
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
