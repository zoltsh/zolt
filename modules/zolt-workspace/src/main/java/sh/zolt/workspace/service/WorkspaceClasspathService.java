package sh.zolt.workspace.service;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkspaceClasspathService {
    private static final Classpath EMPTY_CLASSPATH = new Classpath(List.of());

    private final ZoltLockfileReader lockfileReader;
    private final ClasspathBuilder classpathBuilder;
    private final WorkspaceProcessorClasspathAssembler processorClasspathAssembler;

    public WorkspaceClasspathService() {
        this(new ZoltLockfileReader(), new ClasspathBuilder());
    }

    WorkspaceClasspathService(
            ZoltLockfileReader lockfileReader,
            ClasspathBuilder classpathBuilder) {
        this.lockfileReader = lockfileReader;
        this.classpathBuilder = classpathBuilder;
        this.processorClasspathAssembler = new WorkspaceProcessorClasspathAssembler(classpathBuilder);
    }

    public ClasspathSet classpathsFor(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            String memberPath) {
        return classpathsFor(
                workspace,
                lockfile,
                cacheRoot,
                memberPath,
                new WorkspaceClasspathMemberGraph(workspace));
    }

    public Map<String, ClasspathSet> classpathsForMembers(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            List<String> memberPaths) {
        return classpathsForMembers(
                workspace,
                lockfile,
                cacheRoot,
                memberPaths,
                new LinkedHashSet<>(memberPaths));
    }

    public Map<String, ClasspathSet> classpathsForMembers(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            List<String> memberPaths,
            Set<String> fullClasspathMembers) {
        WorkspaceClasspathMemberGraph memberGraph = new WorkspaceClasspathMemberGraph(workspace);
        Map<String, ClasspathSet> classpathsByMember = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            ClasspathSet classpaths = fullClasspathMembers.contains(memberPath)
                    ? classpathsFor(
                            workspace,
                            lockfile,
                            cacheRoot,
                            memberPath,
                            memberGraph)
                    : mainBuildClasspathsFor(
                            workspace,
                            lockfile,
                            cacheRoot,
                            memberPath,
                            memberGraph);
            classpathsByMember.put(memberPath, classpaths);
        }
        return Collections.unmodifiableMap(classpathsByMember);
    }

    public Map<String, List<ResolvedClasspathPackage>> classpathPackagesForMembers(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            List<String> memberPaths) {
        WorkspaceClasspathMemberGraph memberGraph = new WorkspaceClasspathMemberGraph(workspace);
        Map<String, List<ResolvedClasspathPackage>> packagesByMember = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            packagesByMember.put(
                    memberPath,
                    classpathPackagesFor(workspace, lockfile, cacheRoot, memberPath, memberGraph));
        }
        return Collections.unmodifiableMap(packagesByMember);
    }

    private ClasspathSet mainBuildClasspathsFor(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            String memberPath,
            WorkspaceClasspathMemberGraph memberGraph) {
        Set<String> compileMembers = memberGraph.mainCompile(memberPath);
        ZoltLockfile compileLockfile = new ZoltLockfile(
                lockfile.version(),
                compileClasspathPackagesFor(lockfile, memberPath, compileMembers),
                List.of());
        ClasspathSet compileClasspaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                compileLockfile,
                cacheRoot,
                workspace.root()));
        Classpath processor = processorClasspathAssembler.mergedProcessorClasspath(
                workspace,
                lockfile,
                cacheRoot,
                memberPath,
                memberGraph.compileDependenciesByMember(),
                "processor",
                DependencyScope.PROCESSOR,
                compileClasspaths.processor());
        return new ClasspathSet(
                compileClasspaths.compile(),
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH,
                processor,
                EMPTY_CLASSPATH,
                EMPTY_CLASSPATH);
    }

    private ClasspathSet classpathsFor(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            String memberPath,
            WorkspaceClasspathMemberGraph memberGraph) {
        Set<String> compileMembers = memberGraph.mainCompile(memberPath);
        Set<String> runtimeMembers = memberGraph.mainRuntime(memberPath);
        Set<String> testMembers = memberGraph.test(memberPath);
        Set<String> runtimeVisibleMembers = visibleMembers(memberPath, runtimeMembers);
        Set<String> testVisibleMembers = visibleMembers(memberPath, testMembers);
        ZoltLockfile compileLockfile = new ZoltLockfile(
                lockfile.version(),
                compileClasspathPackagesFor(lockfile, memberPath, compileMembers),
                List.of());
        ZoltLockfile runtimeLockfile = new ZoltLockfile(
                lockfile.version(),
                runtimeClasspathPackagesFor(
                        lockfile.packages(),
                        memberPath,
                        runtimeMembers,
                        runtimeVisibleMembers),
                List.of());
        ZoltLockfile testLockfile = new ZoltLockfile(
                lockfile.version(),
                runtimeClasspathPackagesFor(
                        lockfile.packages(),
                        memberPath,
                        testMembers,
                        testVisibleMembers),
                List.of());
        ClasspathSet compileClasspaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                compileLockfile,
                cacheRoot,
                workspace.root()));
        ClasspathSet runtimeClasspaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                runtimeLockfile,
                cacheRoot,
                workspace.root()));
        ClasspathSet testClasspaths = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                testLockfile,
                cacheRoot,
                workspace.root()));
        Classpath processor = processorClasspathAssembler.mergedProcessorClasspath(
                workspace,
                lockfile,
                cacheRoot,
                memberPath,
                memberGraph.compileDependenciesByMember(),
                "processor",
                DependencyScope.PROCESSOR,
                compileClasspaths.processor());
        Classpath testProcessor = processorClasspathAssembler.mergedProcessorClasspath(
                workspace,
                lockfile,
                cacheRoot,
                memberPath,
                memberGraph.compileDependenciesByMember(),
                "test-processor",
                DependencyScope.TEST_PROCESSOR,
                compileClasspaths.testProcessor());
        return new ClasspathSet(
                compileClasspaths.compile(),
                runtimeClasspaths.runtime(),
                testClasspaths.test(),
                testClasspaths.testCompile(),
                processor,
                testProcessor,
                runtimeClasspaths.quarkusDeployment());
    }

    private List<ResolvedClasspathPackage> classpathPackagesFor(
            Workspace workspace,
            ZoltLockfile lockfile,
            Path cacheRoot,
            String memberPath,
            WorkspaceClasspathMemberGraph memberGraph) {
        Set<String> runtimeMembers = memberGraph.mainRuntime(memberPath);
        Set<String> visibleMembers = visibleMembers(memberPath, runtimeMembers);
        ZoltLockfile runtimeLockfile = new ZoltLockfile(
                lockfile.version(),
                runtimeClasspathPackagesFor(
                        lockfile.packages(),
                        memberPath,
                        runtimeMembers,
                        visibleMembers),
                List.of());
        return LockfileClasspathPackageConverter.classpathPackages(
                runtimeLockfile,
                cacheRoot,
                workspace.root());
    }

    private static List<LockPackage> compileClasspathPackagesFor(
            ZoltLockfile lockfile,
            String memberPath,
            Set<String> dependencyClosure) {
        List<LockPackage> packages = lockfile.packages();
        Set<String> exportedClosure =
                WorkspaceExportedCompileClosure.compute(lockfile, dependencyClosure);
        List<LockPackage> filteredPackages = new ArrayList<>();
        for (LockPackage lockPackage : packages) {
            if (lockPackage.workspace().isPresent()) {
                if (dependencyClosure.contains(lockPackage.workspace().orElseThrow())) {
                    filteredPackages.add(lockPackage);
                }
                continue;
            }

            if (lockPackage.members().isEmpty()
                    || lockPackage.members().contains(memberPath)
                    || exportedClosure.contains(WorkspaceExportedCompileClosure.ref(lockPackage))) {
                filteredPackages.add(lockPackage);
            }
        }
        return filteredPackages;
    }

    private static List<LockPackage> runtimeClasspathPackagesFor(
            List<LockPackage> packages,
            String memberPath,
            Set<String> dependencyClosure,
            Set<String> visibleMembers) {
        List<LockPackage> filteredPackages = new ArrayList<>();
        for (LockPackage lockPackage : packages) {
            if (lockPackage.workspace().isPresent()) {
                if (dependencyClosure.contains(lockPackage.workspace().orElseThrow())) {
                    filteredPackages.add(lockPackage);
                }
                continue;
            }

            if (lockPackage.members().isEmpty()
                    || lockPackage.members().contains(memberPath)
                    || (intersects(lockPackage.members(), visibleMembers)
                            && contributesAcrossWorkspaceBoundary(lockPackage.scope()))) {
                filteredPackages.add(lockPackage);
            }
        }
        return filteredPackages;
    }

    private static boolean contributesAcrossWorkspaceBoundary(DependencyScope scope) {
        return scope == DependencyScope.COMPILE || scope == DependencyScope.RUNTIME;
    }

    private static boolean intersects(List<String> members, Set<String> visibleMembers) {
        for (String member : members) {
            if (visibleMembers.contains(member)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> visibleMembers(String memberPath, Set<String> dependencyMembers) {
        Set<String> visible = new LinkedHashSet<>();
        visible.add(memberPath);
        visible.addAll(dependencyMembers);
        return visible;
    }
}
