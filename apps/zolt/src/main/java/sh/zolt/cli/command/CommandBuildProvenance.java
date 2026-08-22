package sh.zolt.cli.command;

import sh.zolt.cli.ZoltCli;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.provenance.BuildProvenance;
import sh.zolt.provenance.BuildProvenanceReader;
import sh.zolt.provenance.BuildProvenanceSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

public final class CommandBuildProvenance {

    private CommandBuildProvenance() {
    }

    public static BuildProvenance read(Path projectRoot) {
        return new BuildProvenanceReader().read(
                projectRoot,
                ZoltCli.version(),
                resolutionFingerprint(projectRoot),
                System.getenv(),
                Clock.systemUTC());
    }

    public static BuildProvenanceSource source() {
        return BuildProvenanceSource.system(ZoltCli.version(), CommandBuildProvenance::resolutionFingerprint);
    }

    private static Optional<String> resolutionFingerprint(Path projectRoot) {
        Path lockfile = CommandProjectLockfile.path(projectRoot);
        if (!Files.isRegularFile(lockfile)) {
            return Optional.empty();
        }
        return new ZoltLockfileReader().read(lockfile).projectResolutionFingerprint();
    }
}
