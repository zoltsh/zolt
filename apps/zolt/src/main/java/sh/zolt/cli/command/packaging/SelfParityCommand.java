package sh.zolt.cli.command.packaging;

import sh.zolt.build.BuildException;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ManifestGenerationException;
import sh.zolt.build.PackageException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.PrintedUserException;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.resolve.ResolveException;
import sh.zolt.selfhost.SelfHostingParityException;
import sh.zolt.selfhost.SelfHostingParityResult;
import sh.zolt.selfhost.SelfHostingParityService;
import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine;

@Command(
        name = "self-parity",
        description = "Compare bootstrap and Zolt-built jar entries.",
        hidden = true)
public final class SelfParityCommand implements Runnable {
    private final SelfHostingParityService selfHostingParityService;

    @Option(names = "--bootstrap-jar", required = true, description = "Bootstrap-built jar to compare against.")
    private Path bootstrapJar;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = sh.zolt.cache.LocalArtifactCache.defaultRoot();

    @Spec
    private CommandSpec spec;

    public SelfParityCommand() {
        this(new SelfHostingParityService());
    }

    SelfParityCommand(SelfHostingParityService selfHostingParityService) {
        this.selfHostingParityService = selfHostingParityService;
    }

    @Override
    public void run() {
        Path projectRoot = projectDirectory.path();
        try {
            SelfHostingParityResult result = selfHostingParityService.compare(projectRoot, cacheRoot, bootstrapJar);
            if (!result.ok()) {
                CommandHumanOutput output = CommandHumanOutput.errors(spec);
                output.error("Self-hosting parity failed: bootstrap jar and Zolt-built jar contents differ.");
                output.line("Missing from Zolt-built jar:");
                spec.commandLine().getErr().print(formatEntries(result.missingFromZolt()));
                output.line("Extra in Zolt-built jar:");
                spec.commandLine().getErr().print(formatEntries(result.extraInZolt()));
                throw new PrintedUserException(spec.commandLine(), "Self-hosting parity failed.");
            }
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            output.status("Self-hosting parity status", "ok");
            output.context("Bootstrap jar", result.bootstrapJar().toString());
            output.context("Zolt-built jar", result.zoltJar().toString());
            output.statusDetail("ok", "Jar entries match");
        } catch (BuildException
                | JavacException
                | GroovyCompileException
                | ManifestGenerationException
                | PackageException
                | ResourceCopyException
                | SelfHostingParityException
                | SourceDiscoveryException
                | LockfileReadException
                | ResolveException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private static String formatEntries(Set<String> entries) {
        if (entries.isEmpty()) {
            return "  <none>\n";
        }
        StringBuilder output = new StringBuilder();
        entries.stream()
                .limit(50)
                .forEach(entry -> output.append("  - ").append(entry).append('\n'));
        if (entries.size() > 50) {
            output.append("  ... ").append(entries.size() - 50).append(" more\n");
        }
        return output.toString();
    }
}
