package sh.zolt.workspace.service;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkspaceClasspathService {
    private static final Classpath EMPTY_CLASSPATH = new Classpath(List.of());

    private final ZoltLockfileReader lockfileReader;
    private final ClasspathBuilder classpathBuilder;
    private final WorkspaceProcessorClasspathAssembler processorClasspathAssembler;
    private final WorkspaceClasspathLockFactory lockFactory;

    public WorkspaceClasspathService() {
        this(new ZoltLockfileReader(), new ClasspathBuilder());
    }

    WorkspaceClasspathService(
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder) {
        this.lockfileReader = lockfileReader;
        this.classpathBuilder = classpathBuilder;
        this.processorClasspathAssembler = new WorkspaceProcessorClasspathAssembler(classpathBuilder);
        this.lockFactory = new WorkspaceClasspathLockFactory();
    }

    public ClasspathSet classpathsFor(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            String memberPath) {
        WorkspaceExecutionContext context = new WorkspaceExecutionContext(workspace, lockfile, cacheRoot);
        return classpathsFor(context, memberPath, WorkspaceBuildRequirements.testRun());
    }

    public Map<String, ClasspathSet> classpathsForMembers(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            List<String> memberPaths) {
        Map<String, WorkspaceBuildRequirements> requirements = new LinkedHashMap<>();
        memberPaths.forEach(member -> requirements.put(member, WorkspaceBuildRequirements.testRun()));
        return classpathsForMembers(
                new WorkspaceExecutionContext(workspace, lockfile, cacheRoot),
                memberPaths,
                requirements);
    }

    public Map<String, ClasspathSet> classpathsForMembers(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            List<String> memberPaths,
            Set<String> fullClasspathMembers) {
        Map<String, WorkspaceBuildRequirements> requirements = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            requirements.put(
                    memberPath,
                    fullClasspathMembers.contains(memberPath)
                            ? WorkspaceBuildRequirements.testRun()
                            : WorkspaceBuildRequirements.mainBuild());
        }
        return classpathsForMembers(
                new WorkspaceExecutionContext(workspace, lockfile, cacheRoot),
                memberPaths,
                requirements);
    }

    Map<String, ClasspathSet> classpathsForMembers(
            WorkspaceExecutionContext context,
            List<String> memberPaths,
            Map<String, WorkspaceBuildRequirements> requirementsByMember) {
        Map<String, ClasspathSet> classpathsByMember = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            WorkspaceBuildRequirements requirements = requirementsByMember.getOrDefault(
                    memberPath,
                    WorkspaceBuildRequirements.mainBuild());
            classpathsByMember.put(memberPath, classpathsFor(context, memberPath, requirements));
        }
        return Collections.unmodifiableMap(classpathsByMember);
    }

    public Map<String, List<ResolvedClasspathPackage>> classpathPackagesForMembers(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            List<String> memberPaths) {
        return classpathPackagesForMembers(
                new WorkspaceExecutionContext(workspace, lockfile, cacheRoot),
                memberPaths);
    }

    Map<String, List<ResolvedClasspathPackage>> classpathPackagesForMembers(
            WorkspaceExecutionContext context,
            List<String> memberPaths) {
        Map<String, List<ResolvedClasspathPackage>> packagesByMember = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            packagesByMember.put(
                    memberPath,
                    context.classpathPackages(
                            memberPath,
                            () -> LockfileClasspathPackageConverter.classpathPackages(
                                    packageLockFor(context, memberPath),
                                    context.cacheRoot(),
                                    context.workspace().root())));
        }
        return Collections.unmodifiableMap(packagesByMember);
    }

    /**
     * Returns the exact per-member package/runtime closure used to create workspace package inputs.
     */
    public Map<String, ZoltLockfile> packageLocksForMembers(
            Workspace workspace,
            ZoltLockfile lockfile,
            List<String> memberPaths) {
        return packageLocksForMembers(
                new WorkspaceExecutionContext(workspace, lockfile, Path.of(".")),
                memberPaths);
    }

    public Map<String, ZoltLockfile> packageLocksForMembers(
            WorkspaceExecutionContext context,
            List<String> memberPaths) {
        Map<String, ZoltLockfile> locksByMember = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            locksByMember.put(memberPath, packageLockFor(context, memberPath));
        }
        return Collections.unmodifiableMap(locksByMember);
    }

    private ClasspathSet classpathsFor(
            WorkspaceExecutionContext context,
            String memberPath,
            WorkspaceBuildRequirements requirements) {
        return context.classpaths(
                memberPath,
                requirements,
                () -> calculateClasspaths(context, memberPath, requirements));
    }

    private ClasspathSet calculateClasspaths(
            WorkspaceExecutionContext context,
            String memberPath,
            WorkspaceBuildRequirements requirements) {
        Workspace workspace = context.workspace();
        ZoltLockfile lockfile = context.lockfile();
        Path cacheRoot = context.cacheRoot();
        WorkspaceClasspathMemberGraph memberGraph = context.memberGraph();
        ClasspathSet compileClasspaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                lockFactory.compileLock(lockfile, memberPath, memberGraph),
                cacheRoot,
                workspace.root()));
        ClasspathSet runtimeClasspaths = requirements.mainRuntimeClasspath()
                ? classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                        lockFactory.runtimeLock(lockfile, memberPath, memberGraph),
                        cacheRoot,
                        workspace.root()))
                : emptyClasspaths();
        ClasspathSet testClasspaths = requirements.testCompileClasspath()
                ? classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                        lockFactory.testLock(lockfile, memberPath, memberGraph),
                        cacheRoot,
                        workspace.root()))
                : emptyClasspaths();
        Classpath processor = requirements.processorClasspath()
                ? processorClasspathAssembler.mergedProcessorClasspath(
                        workspace,
                        lockfile,
                        cacheRoot,
                        memberPath,
                        memberGraph.compileDependenciesByMember(),
                        "processor",
                        DependencyScope.PROCESSOR,
                        compileClasspaths.processor())
                : EMPTY_CLASSPATH;
        Classpath testProcessor = requirements.testProcessorClasspath()
                ? processorClasspathAssembler.mergedProcessorClasspath(
                        workspace,
                        lockfile,
                        cacheRoot,
                        memberPath,
                        memberGraph.compileDependenciesByMember(),
                        "test-processor",
                        DependencyScope.TEST_PROCESSOR,
                        testClasspaths.testProcessor())
                : EMPTY_CLASSPATH;
        return new ClasspathSet(
                compileClasspaths.compile(),
                requirements.mainRuntimeClasspath() ? runtimeClasspaths.runtime() : EMPTY_CLASSPATH,
                requirements.testRuntimeClasspath() ? testClasspaths.test() : EMPTY_CLASSPATH,
                requirements.testCompileClasspath() ? testClasspaths.testCompile() : EMPTY_CLASSPATH,
                processor,
                testProcessor,
                requirements.mainRuntimeClasspath()
                        ? runtimeClasspaths.quarkusDeployment()
                        : EMPTY_CLASSPATH);
    }

    private static ClasspathSet emptyClasspaths() {
        return new ClasspathSet(
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH);
    }

    private ZoltLockfile packageLockFor(
            WorkspaceExecutionContext context,
            String memberPath) {
        return context.packageLock(
                memberPath,
                () -> lockFactory.packageLock(
                        context.lockfile(),
                        memberPath,
                        context.memberGraph()));
    }
}
