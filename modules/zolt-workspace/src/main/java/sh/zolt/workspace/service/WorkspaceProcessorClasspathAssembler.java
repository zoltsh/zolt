package sh.zolt.workspace.service;

import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Assembles the {@code -processorpath} contribution from workspace-member annotation processors.
 *
 * <p>A workspace processor edge ({@code [dependencies.processor] "x" = { workspace = "modules/proc" }})
 * makes the processor member a build prerequisite, but its compiled output and transitive
 * dependencies must land on the consumer's processor path ONLY — never on its compile, runtime,
 * test, package, or native classpaths. The processor member is therefore deliberately excluded from
 * the consumer's compile/runtime dependency closure (see
 * {@link WorkspaceClasspathMemberGraph}); here we gather the processor member's full
 * transitive package set and re-scope it to the processor lane so the isolation invariant holds
 * across the workspace edge.
 */
final class WorkspaceProcessorClasspathAssembler {
    private final ClasspathBuilder classpathBuilder;

    WorkspaceProcessorClasspathAssembler(ClasspathBuilder classpathBuilder) {
        this.classpathBuilder = classpathBuilder;
    }

    /**
     * Returns {@code externalProcessors} (the external/published processor jars resolved for the
     * member) merged with the compiled output and transitive dependencies of every workspace
     * processor member the consumer declares for the given edge scope, all re-scoped onto the
     * processor lane. Artifacts are verified through the command's shared index, so processor lanes
     * reuse the digests the compile and test lanes already computed.
     */
    Classpath mergedProcessorClasspath(
            WorkspaceExecutionContext context,
            String memberPath,
            String edgeScope,
            DependencyScope targetScope,
            Classpath externalProcessors) {
        Workspace workspace = context.workspace();
        ZoltLockfile lockfile = context.lockfile();
        Set<String> processorMembers = WorkspaceCanonicalBuildPolicy.processorMemberClosure(
                workspace,
                memberPath,
                edgeScope,
                context.memberGraph().compileDependenciesByMember());
        if (processorMembers.isEmpty()) {
            return externalProcessors;
        }
        List<LockPackage> processorPackages = processorClasspathPackagesFor(
                lockfile,
                processorMembers,
                targetScope);
        ZoltLockfile processorLockfile = new ZoltLockfile(
                lockfile.version(),
                processorPackages,
                List.of());
        ClasspathSet built = classpathBuilder.build(LockfileClasspathPackageConverter.classpathPackages(
                processorLockfile,
                context.cacheRoot(),
                workspace.root(),
                context.artifactIndex()));
        Classpath memberProcessors =
                targetScope == DependencyScope.TEST_PROCESSOR ? built.testProcessor() : built.processor();
        return mergeClasspaths(externalProcessors, memberProcessors);
    }

    private static List<LockPackage> processorClasspathPackagesFor(
            ZoltLockfile lockfile,
            Set<String> processorMembers,
            DependencyScope targetScope) {
        List<LockPackage> packages = lockfile.packages();
        sh.zolt.lockfile.LockMemberGraphIndex memberGraphs =
                new sh.zolt.lockfile.LockMemberGraphIndex(
                        lockfile.memberGraphs(), packages);
        List<LockPackage> filteredPackages = new ArrayList<>();
        for (LockPackage lockPackage : packages) {
            if (lockPackage.workspace().isPresent()) {
                if (processorMembers.contains(lockPackage.workspace().orElseThrow())) {
                    filteredPackages.add(rescoped(lockPackage, targetScope));
                }
                continue;
            }
            if (hasNonOptionalContributor(
                            lockPackage,
                            processorMembers,
                            memberGraphs)
                    && contributesToProcessorRuntime(lockPackage.scope())) {
                filteredPackages.add(rescoped(lockPackage, targetScope));
            }
        }
        return filteredPackages;
    }

    private static boolean contributesToProcessorRuntime(DependencyScope scope) {
        return scope == DependencyScope.COMPILE || scope == DependencyScope.RUNTIME;
    }

    private static LockPackage rescoped(LockPackage lockPackage, DependencyScope scope) {
        if (lockPackage.scope() == scope) {
            return lockPackage;
        }
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                scope,
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                lockPackage.dependencies(),
                lockPackage.members(),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

    private static Classpath mergeClasspaths(Classpath first, Classpath second) {
        LinkedHashSet<Path> entries = new LinkedHashSet<>(first.entries());
        entries.addAll(second.entries());
        return new Classpath(List.copyOf(entries));
    }

    private static boolean hasNonOptionalContributor(
            LockPackage lockPackage,
            Set<String> visibleMembers,
            sh.zolt.lockfile.LockMemberGraphIndex memberGraphs) {
        for (String member : lockPackage.members()) {
            if (visibleMembers.contains(member)
                    && !memberGraphs.optionalOnlyFor(member, lockPackage)) {
                return true;
            }
        }
        return false;
    }
}
