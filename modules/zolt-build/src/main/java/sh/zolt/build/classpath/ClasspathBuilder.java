package sh.zolt.build.classpath;

import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

public final class ClasspathBuilder {
    public ClasspathSet build(List<ResolvedClasspathPackage> packages) {
        List<ResolvedClasspathPackage> sorted = packages.stream()
                .sorted(Comparator.comparing(ClasspathBuilder::sortKey))
                .toList();
        return new ClasspathSet(
                classpath(sorted, DependencyScope::entersMainCompileClasspath),
                classpath(sorted, DependencyScope::entersMainRuntimeClasspath),
                classpath(sorted, DependencyScope::entersTestRuntimeClasspath),
                classpath(sorted, DependencyScope::entersTestCompileClasspath),
                classpath(sorted, DependencyScope::entersMainProcessorClasspath),
                classpath(sorted, DependencyScope::entersTestProcessorClasspath),
                classpath(sorted, scope -> scope == DependencyScope.QUARKUS_DEPLOYMENT));
    }

    private static Classpath classpath(
            List<ResolvedClasspathPackage> packages,
            Predicate<DependencyScope> includeScope) {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        for (ResolvedClasspathPackage dependency : packages) {
            if (includeScope.test(dependency.scope())) {
                entries.add(dependency.resolvedPackage().jarPath());
            }
        }
        return new Classpath(List.copyOf(entries));
    }

    private static String sortKey(ResolvedClasspathPackage dependency) {
        return dependency.resolvedPackage().packageId()
                + ":"
                + dependency.resolvedPackage().selectedVersion()
                + ":"
                + dependency.scope();
    }
}
