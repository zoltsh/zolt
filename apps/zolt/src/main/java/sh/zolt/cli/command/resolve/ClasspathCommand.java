package sh.zolt.cli.command.resolve;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.classpath.ClasspathFormatter;
import sh.zolt.build.classpath.ClasspathLaneAuditFormatter;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandProjectLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "classpath", description = "Print a classpath from zolt.lock.")
public final class ClasspathCommand implements Runnable {
    private final ZoltLockfileReader lockfileReader;
    private final ManifestProjectLoader projectLoader;
    private final ClasspathLaneAuditFormatter auditFormatter;
    private final ClasspathBuilder classpathBuilder;
    private final ClasspathFormatter classpathFormatter;
    private final CommandLockfiles lockfiles;

    enum Kind {
        COMPILE("compile"),
        RUNTIME("runtime"),
        TEST("test"),
        PROCESSOR("processor"),
        TEST_PROCESSOR("test-processor"),
        QUARKUS_DEPLOYMENT("quarkus-deployment"),
        AUDIT("audit");

        private static final String SUPPORTED =
                "compile, runtime, test, processor, test-processor, quarkus-deployment, or audit";

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        private static Kind parse(String value) {
            for (Kind kind : values()) {
                if (kind.label.equalsIgnoreCase(value)) {
                    return kind;
                }
            }
            throw new ClasspathCommandException(
                    "Unknown classpath kind `" + value
                            + "`. Use " + SUPPORTED + ".");
        }
    }

    enum Format {
        TEXT,
        JSON
    }

    @Parameters(
            index = "0",
            paramLabel = "compile|runtime|test|processor|test-processor|quarkus-deployment|audit",
            description = "Classpath kind to print, or audit to inspect all Zolt-owned lanes.")
    private String kind;

    @Option(names = "--format", description = "Output format for audit: text or json.")
    private Format format = Format.TEXT;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = sh.zolt.cache.LocalArtifactCache.defaultRoot();

    @Spec
    private CommandSpec spec;

    public ClasspathCommand() {
        this(
                new ZoltLockfileReader(),
                new ManifestProjectLoader(),
                new ClasspathLaneAuditFormatter(),
                new ClasspathBuilder(),
                new ClasspathFormatter(),
                new CommandLockfiles());
    }

    ClasspathCommand(
            ZoltLockfileReader lockfileReader,
            ManifestProjectLoader projectLoader,
            ClasspathLaneAuditFormatter auditFormatter,
            ClasspathBuilder classpathBuilder,
            ClasspathFormatter classpathFormatter,
            CommandLockfiles lockfiles) {
        this.lockfileReader = lockfileReader;
        this.projectLoader = projectLoader;
        this.auditFormatter = auditFormatter;
        this.classpathBuilder = classpathBuilder;
        this.classpathFormatter = classpathFormatter;
        this.lockfiles = lockfiles;
    }

    @Override
    public void run() {
        try {
            Path projectRoot = projectDirectory.path();
            Path configPath = projectRoot.resolve("zolt.toml");
            if (Files.isRegularFile(configPath)) {
                // A workspace member's lock is the workspace root's, and only the workspace path
                // refreshes it, so a read-only classpath query neither gates on its freshness nor
                // consults a member-local zolt.lock (design §6.9).
                var artifactIndex = CommandProjectLockfile.governedByWorkspace(projectRoot)
                        ? new VerifiedArtifactIndex()
                        : lockfiles.requireFreshLockfile(
                                projectRoot, projectLoader.load(projectRoot), cacheRoot, false);
                printClasspath(projectRoot, cacheRoot, artifactIndex);
                return;
            }
            printClasspath(projectRoot, cacheRoot, new VerifiedArtifactIndex());
        } catch (ArtifactCacheException
                | ClasspathCommandException
                | LockfileReadException
                | ResolveException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private void printClasspath(
            Path projectRoot,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
            ZoltLockfile lockfile = lockfileReader.read(
                    CommandProjectLockfile.path(projectRoot));
            Kind parsedKind = Kind.parse(kind);
            if (parsedKind == Kind.AUDIT) {
                String output = format == Format.JSON
                        ? auditFormatter.formatJson(lockfile)
                        : auditFormatter.formatText(lockfile);
                CommandOutput.printAndFlush(spec, output);
                return;
            }
            if (format == Format.JSON) {
                throw new ClasspathCommandException(
                        "`zolt classpath --format json` is supported for `audit` only. "
                                + "Use `zolt classpath audit --format json`.");
            }
            ClasspathSet classpaths = classpathBuilder.build(
                    LockfileClasspathPackageConverter.classpathPackages(lockfile, cacheRoot, artifactIndex));
            String output = classpathFormatter.format(switch (parsedKind) {
                case COMPILE -> classpaths.compile();
                case RUNTIME -> classpaths.runtime();
                case TEST -> classpaths.test();
                case PROCESSOR -> classpaths.processor();
                case TEST_PROCESSOR -> classpaths.testProcessor();
                case QUARKUS_DEPLOYMENT -> classpaths.quarkusDeployment();
                case AUDIT -> throw new ClasspathCommandException("Classpath audit should be handled before formatting.");
            });
            CommandOutput.printAndFlush(spec, output);
    }

    private static final class ClasspathCommandException extends RuntimeException {
        private ClasspathCommandException(String message) {
            super(message);
        }
    }
}
