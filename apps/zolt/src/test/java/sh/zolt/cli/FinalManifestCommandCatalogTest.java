package sh.zolt.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import sh.zolt.toml.schema.FinalManifestSchema;

final class FinalManifestCommandCatalogTest {
    @Test
    void finalManifestReservationsMatchEveryRootCommand() {
        List<String> registered = List.copyOf(new TreeSet<>(
                ZoltCli.newCommandLine().getSubcommands().keySet()));
        List<String> reserved = FinalManifestSchema.registry()
                .symbols()
                .family("built-in-command")
                .orElseThrow()
                .values();

        assertEquals(
                registered,
                reserved,
                "Update the final manifest built-in-command family whenever the root CLI changes.");
    }
}
