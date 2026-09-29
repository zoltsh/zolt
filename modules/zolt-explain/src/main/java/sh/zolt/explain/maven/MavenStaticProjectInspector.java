package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.child;
import static sh.zolt.explain.maven.MavenXml.texts;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;
import sh.zolt.explain.MigrationExplainException;
import sh.zolt.project.VersionPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public final class MavenStaticProjectInspector {
    private final MavenExternalParentResolver externalParentResolver;

    public MavenStaticProjectInspector() {
        this(null);
    }

    /**
     * @param externalParentResolver fetches external parent chains for {@code --resolve-external-parents};
     *     {@code null} keeps the audit fully offline so external parents stay unresolved (the default).
     */
    public MavenStaticProjectInspector(MavenExternalParentResolver externalParentResolver) {
        this.externalParentResolver = externalParentResolver;
    }

    public MavenInspectionResult inspect(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path rootPom = normalizedRoot.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            throw new MigrationExplainException(
                    "Could not inspect Maven project. Expected pom.xml at " + rootPom
                            + ". Run zolt explain from a Maven project root or pass --cwd.");
        }
        if (!Files.isRegularFile(rootPom)) {
            throw new MigrationExplainException(
                    "Could not inspect Maven project. Expected pom.xml to be a regular file: " + rootPom);
        }

        List<MavenProjectInspection> projects = new ArrayList<>();
        List<ExplainSignal> signals = new ArrayList<>();
        MavenReactorPoms reactor = new MavenReactorPoms();
        List<MavenPomNode> poms = new ArrayList<>();
        collectPom(normalizedRoot, normalizedRoot, poms, signals, reactor, new LinkedHashSet<>());
        MavenReactorParentRecovery recovery = externalParentResolver == null
                ? null
                : MavenReactorParentRecovery.create(
                        externalParentResolver, poms.stream().map(MavenPomNode::project).toList());
        for (MavenPomNode pom : poms) {
            RecoveredParentMetadata recovered = recovery == null
                    ? RecoveredParentMetadata.none()
                    : recovery.recover(pom.project(), reactor);
            MavenProjectInspection inspection = MavenProjectInspectionBuilder.build(
                    pom.pom(),
                    pom.relativePath(),
                    pom.directory(),
                    pom.project(),
                    reactor,
                    recovered);
            projects.add(inspection);
            signals.addAll(signalsFor(pom.projectLabel(), inspection, pom.directory(), recovered));
        }
        projects.sort(Comparator.comparing(project -> project.path().toString()));
        return new MavenInspectionResult(normalizedRoot, projects, ExplainSignals.sorted(signals));
    }

    private void collectPom(
            Path root,
            Path projectDirectory,
            List<MavenPomNode> poms,
            List<ExplainSignal> signals,
            MavenReactorPoms reactor,
            Set<Path> seenPoms) {
        Path pom = projectDirectory.resolve("pom.xml");
        Path normalizedPom = pom.toAbsolutePath().normalize();
        if (!seenPoms.add(normalizedPom)) {
            return;
        }
        Document document = document(pom);
        Element project = document.getDocumentElement();

        Path relativePath = relativePath(root, projectDirectory);
        String projectLabel = relativePath.toString().isBlank() ? "." : relativePath.toString();
        reactor.register(project, relativePath);
        poms.add(new MavenPomNode(pom, projectDirectory, relativePath, projectLabel, project));

        for (String module : texts(child(project, "modules"), "module")) {
            Path moduleDirectory = projectDirectory.resolve(module).normalize();
            Path modulePom = moduleDirectory.resolve("pom.xml");
            if (Files.isRegularFile(modulePom)) {
                collectPom(root, moduleDirectory, poms, signals, reactor, seenPoms);
            } else {
                signals.add(ExplainSignals.MAVEN_MODULE_MISSING_POM.signal(
                        projectLabel,
                        "Module `" + module + "` does not contain a readable pom.xml."));
            }
        }
    }

    private Document document(Path pom) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ThrowingXmlErrorHandler());
            try (InputStream inputStream = Files.newInputStream(pom)) {
                return builder.parse(inputStream);
            }
        } catch (ParserConfigurationException exception) {
            throw new MigrationExplainException("Could not configure secure Maven POM XML parser.", exception);
        } catch (SAXException exception) {
            throw new MigrationExplainException(
                    "Could not inspect Maven project. Fix malformed POM XML before running zolt explain: " + pom,
                    exception);
        } catch (IOException exception) {
            throw new MigrationExplainException("Could not read Maven POM for zolt explain: " + pom, exception);
        }
    }

    private List<ExplainSignal> signalsFor(
            String project,
            MavenProjectInspection inspection,
            Path projectDirectory,
            RecoveredParentMetadata recovered) {
        List<ExplainSignal> signals = new ArrayList<>();
        List<String> profileModules = MavenProfileSignals.modules(inspection);
        if ("pom".equals(inspection.packaging()) && (!inspection.modules().isEmpty() || !profileModules.isEmpty())) {
            int members = inspection.modules().size();
            signals.add(ExplainSignals.MAVEN_REACTOR_DETECTED.signal(
                    project,
                    MavenSignalRules.reactorMessage(members, profileModules)));
        }
        if ("pom".equals(inspection.packaging())
                && inspection.modules().isEmpty()
                && profileModules.isEmpty()
                && inspection.sourceRoots().isEmpty()
                && (!inspection.dependencyManagement().isEmpty() || !inspection.importedBoms().isEmpty())) {
            int pins = inspection.dependencyManagement().size();
            int imports = inspection.importedBoms().size();
            signals.add(ExplainSignals.MAVEN_BOM_DETECTED.signal(
                    project,
                    "dependencyManagement BOM detected: " + pins + " version pin(s), " + imports
                            + " import-scope BOM(s)."));
        }
        if (unsupportedPackaging(inspection.packaging())) {
            signals.add(ExplainSignals.MAVEN_PACKAGING_UNSUPPORTED.signal(
                    project,
                    "Packaging `" + inspection.packaging() + "` needs an explicit Zolt packaging primitive."));
        }
        if ("unknown".equals(inspection.javaVersion())) {
            signals.add(ExplainSignals.MAVEN_JAVA_VERSION_UNKNOWN.signal(
                    project,
                    "Maven Java version could not be resolved from compiler properties or plugin configuration."));
        }
        MavenModuleInfoDetection.moduleInfoUnderJavaBelow9(inspection, projectDirectory).ifPresent(root ->
                signals.add(ExplainSignals.MAVEN_JPMS_MODULE_INFO_DETECTED.signal(
                        project,
                        "A `module-info.java` under source root `" + root
                                + "` requires Java 9+, but the audited Java version is `"
                                + inspection.javaVersion()
                                + "`; raise the Java version to 9+ or add multi-release handling.")));
        if (!inspection.testJavaVersion().isBlank()
                && !inspection.testJavaVersion().equals(inspection.javaVersion())) {
            signals.add(ExplainSignals.MAVEN_TEST_JAVA_VERSION_DIVERGENT.signal(
                    project,
                    "Maven test Java version `" + inspection.testJavaVersion()
                            + "` differs from main Java version `" + inspection.javaVersion() + "`."));
        }
        MavenPlatformApiHostCandidate.signal(project, inspection).ifPresent(signals::add);
        boolean hasUnresolvedParent = inspection.parents().stream().anyMatch(parent -> !parent.resolved());
        for (MavenDependencyInspection dependency
                : MavenSignalRules.concat(inspection.dependencies(), inspection.dependencyManagement())) {
            // An unresolved ${...} is not a genuine dynamic/SNAPSHOT/range version; the emit path
            // surfaces it as an honest review comment instead of a wrong non-determinism blocker.
            if (dependency.version().contains("${")) {
                signals.add(ExplainSignals.MAVEN_DEPENDENCY_UNRESOLVED_VERSION.signal(
                        project,
                        "Dependency `" + dependency.coordinate() + "` keeps unresolved version `"
                                + dependency.version() + "`."));
                continue;
            }
            if (dependency.version().isBlank() && hasUnresolvedParent) {
                signals.add(ExplainSignals.MAVEN_DEPENDENCY_MISSING_VERSION.signal(
                        project,
                        "Dependency `" + dependency.coordinate()
                                + "` has no statically resolved version because external parent metadata was not loaded."));
                continue;
            }
            Optional<VersionPolicy.Violation> violation = unsupportedExternalVersion(dependency.version());
            if (violation.isPresent()) {
                signals.add(ExplainSignals.MAVEN_DEPENDENCY_DYNAMIC_VERSION.signal(
                        project,
                        "Dependency `"
                                + dependency.coordinate()
                                + "` uses dynamic version `"
                                + dependency.version()
                                + "` (version-policy rule: "
                                + violation.orElseThrow().rule()
                                + ")."));
            }
        }
        for (MavenPluginInspection plugin : inspection.plugins()) {
            if (plugin.pluginManagement()) {
                continue;
            }
            if (MavenSignalRules.replacedGroovyCompilation(plugin)) {
                continue;
            }
            if (MavenSignalRules.gmavenPlusPlugin(plugin.coordinate())
                    && (!plugin.phases().isEmpty() || !plugin.goals().isEmpty())) {
                signals.add(ExplainSignals.MAVEN_PLUGIN_LIFECYCLE_BINDING.signal(
                        project,
                        "Plugin `" + plugin.coordinate()
                                + "` declares gmavenplus behavior outside Zolt's replaced compile/compileTests shape"
                                + " with goal(s) " + plugin.goals()
                                + MavenSignalRules.phaseSuffix(plugin) + "."));
            } else if (MavenSignalRules.boundedKotlinJvmCompilation(plugin)) {
                signals.add(ExplainSignals.MAVEN_KOTLIN_MANUAL_MIGRATION.signal(
                        project,
                        "Plugin `" + plugin.coordinate()
                                + "` declares bounded Kotlin/JVM compilation that requires manual Zolt migration."));
            } else if (MavenSignalRules.kotlinMavenPlugin(plugin.coordinate())
                    || MavenSignalRules.unsupportedLanguagePlugin(plugin.coordinate())) {
                signals.add(ExplainSignals.MAVEN_LANGUAGE_UNSUPPORTED.signal(
                        project,
                        "Plugin `" + plugin.coordinate()
                                + "` declares behavior outside Zolt's bounded Kotlin/JVM and supported"
                                + " Java/Groovy build surface, or an Android build."));
            } else if (MavenSignalRules.unsupportedFrameworkNativePlugin(plugin)) {
                signals.add(ExplainSignals.MAVEN_FRAMEWORK_NATIVE_UNSUPPORTED.signal(
                        project,
                        "Plugin `" + plugin.coordinate() + "` declares framework AOT/native behavior that Zolt does not execute as Maven lifecycle behavior; migrate supported cases to typed Zolt framework settings."));
            } else if (!plugin.execInvocations().isEmpty()) {
                signals.addAll(MavenExecPluginSignals.signals(project, plugin));
            } else if (plugin.databaseBackedCodegen()) {
                signals.add(ExplainSignals.MAVEN_PLUGIN_EXEC_NONDETERMINISTIC.signal(
                        project,
                        "Plugin `" + plugin.coordinate() + "` generates from a live database"
                                + MavenSignalRules.phaseSuffix(plugin)
                                + ", so its output is not reproducible from committed inputs."));
            } else if (MavenSignalRules.knownPlugin(plugin.coordinate()) && (!plugin.phases().isEmpty() || !plugin.goals().isEmpty())) {
                signals.add(ExplainSignals.MAVEN_PLUGIN_STATIC_SIGNAL.signal(
                        project,
                        "Plugin `" + plugin.coordinate() + "` declares statically visible Maven behavior"
                                + MavenSignalRules.phaseSuffix(plugin) + "."));
            } else if (!plugin.phases().isEmpty()) {
                signals.add(ExplainSignals.MAVEN_PLUGIN_LIFECYCLE_BINDING.signal(
                        project,
                        "Plugin `" + plugin.coordinate() + "` runs in effective lifecycle phase(s) " + plugin.phases() + "."));
            } else if (!MavenSignalRules.knownPlugin(plugin.coordinate())) {
                signals.add(ExplainSignals.MAVEN_PLUGIN_STATIC_SIGNAL.signal(
                        project,
                        "Plugin `" + plugin.coordinate() + "` is declared and was not executed."));
            }
        }
        for (MavenAnnotationProcessorInspection processor : inspection.annotationProcessors()) {
            signals.add(ExplainSignals.MAVEN_ANNOTATION_PROCESSOR_PATH.signal(
                    project,
                    "Annotation processor path `" + processor.coordinate()
                            + "` is configured in maven-compiler-plugin."));
        }
        for (MavenParentInspection parent : inspection.parents()) {
            if (!parent.resolved()) {
                signals.add(ExplainSignals.MAVEN_PARENT_UNRESOLVED.signal(
                        project,
                        "Parent `" + parent.coordinate()
                                + "` was not resolved from the reactor; inherited metadata remains unknown.",
                        recovered.reviewNote()));
            }
            if (snapshotVersion(parent.version())) {
                signals.add(ExplainSignals.MAVEN_PARENT_SNAPSHOT.signal(
                        project,
                        "Parent `" + parent.coordinate() + "` uses a SNAPSHOT version."));
            }
        }
        if (recovered.resolved() && !recovered.fetchedArtifacts().isEmpty()) {
            signals.add(MavenReactorParentRecovery.recoveredSignal(project, recovered));
        }
        for (MavenRepositoryInspection repository : inspection.repositories()) {
            String label = repositoryLabel(repository);
            signals.add(ExplainSignals.MAVEN_REPOSITORY_DECLARED.signal(
                    project,
                    "Maven " + label + " `" + repository.id() + "` declares " + repository.url() + "."));
            if (repository.snapshotsEnabled()) {
                signals.add(ExplainSignals.MAVEN_REPOSITORY_SNAPSHOTS_ENABLED.signal(
                        project,
                        "Maven " + label + " `" + repository.id() + "` has snapshots enabled."));
            }
        }
        signals.addAll(MavenProfileSignals.signalsFor(project, inspection));
        return signals;
    }

    private static Path relativePath(Path root, Path projectDirectory) {
        Path relative = root.relativize(projectDirectory);
        return relative.toString().isBlank() ? Path.of(".") : relative;
    }

    private static boolean unsupportedPackaging(String packaging) {
        return !Set.of("jar", "pom").contains(packaging);
    }

    private static Optional<VersionPolicy.Violation> unsupportedExternalVersion(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        return VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, version);
    }

    private static boolean snapshotVersion(String version) {
        return version.toUpperCase().contains("SNAPSHOT");
    }

    private static String repositoryLabel(MavenRepositoryInspection repository) {
        return repository.pluginRepository() ? "pluginRepository" : "repository";
    }

    private record MavenPomNode(
            Path pom,
            Path directory,
            Path relativePath,
            String projectLabel,
            Element project) {
    }
}
