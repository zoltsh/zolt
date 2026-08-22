package sh.zolt.cli.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class ZoltNativeImageConfigurationTest {
    @Test
    void nativeZoltEnablesHttpsForReleaseUpdateDownloads() {
        AuthoredNativeImage nativeImage = new ManifestProjectConfigLoader()
                .document(zoltAppConfigPath())
                .authored()
                .packaging()
                .nativeImage()
                .orElseThrow(() -> new AssertionError("apps/zolt must declare [native] settings"));

        assertTrue(
                nativeImage.args().orElse(List.of()).contains("--enable-url-protocols=https"),
                "native zolt must keep HTTPS URL protocol support for release channel and archive downloads");
    }

    private static Path zoltAppConfigPath() {
        Path workspacePath = Path.of("apps/zolt/zolt.toml");
        if (Files.exists(workspacePath)) {
            return workspacePath;
        }
        return Path.of("zolt.toml");
    }
}
