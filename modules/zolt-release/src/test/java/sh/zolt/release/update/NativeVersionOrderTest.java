package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeVersionOrderTest extends NativeUpdateServiceTestCase {
    @Test
    void pruneKeepsTheNewerNumericPatch() throws Exception {
        InstalledFixture installed = install("1.0.11");
        writeFakeZolt(installed.installRoot().resolve("versions/1.0.9/bin/zolt"), "1.0.9");
        writeFakeZolt(installed.installRoot().resolve("versions/1.0.10/bin/zolt"), "1.0.10");
        NativeVersionPruneResult result = new NativeVersionService().prune(
                new NativeVersionPruneRequest(installed.installRoot(), installed.binLink(), 2, true));
        assertEquals(List.of("1.0.9"), result.prunedVersions().stream().map(NativeInstalledVersion::version).toList());
    }

    @Test
    void precedenceCoversNumericCorePreviewAndZapWithoutInferringCommitAge() {
        List<String> expected = List.of("legacy", "1.0.9", "1.0.10", "1.9.0", "1.10.0-alpha.2",
                "1.10.0-alpha.10", "1.10.0-beta.1", "1.10.0-rc.1",
                "1.10.0-zap.20261031.ffffffffffff", "1.10.0-zap.20261101.aaaaaaaaaaaa",
                "1.10.0-zap.20261101.bbbbbbbbbbbb", "1.10.0", "2.0.0-alpha.1");
        List<String> shuffled = new ArrayList<>(expected);
        Collections.reverse(shuffled);
        shuffled.sort(NativeVersionOrder.INSTANCE);
        assertEquals(expected, shuffled);
    }

    @Test
    void metadataOnlyBreaksEqualPrecedenceAndInvalidSemverNamesRemainLegacy() {
        List<String> expected = List.of("1.0.0+.", "1.0.0-alpha.01", "1.0.0-alpha.1",
                "1.0.0", "1.0.0+a", "1.0.0+b", "1.0.1");
        List<String> shuffled = new ArrayList<>(expected);
        Collections.reverse(shuffled);
        shuffled.sort(NativeVersionOrder.INSTANCE);
        assertEquals(expected, shuffled);
    }

}
