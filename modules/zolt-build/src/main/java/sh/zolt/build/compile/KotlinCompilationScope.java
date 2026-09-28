package sh.zolt.build.compile;

import sh.zolt.dependency.DependencyScope;

/** The source-set contract used to select Kotlin runtime visibility and diagnostics. */
public enum KotlinCompilationScope {
    MAIN("main", "[dependencies]"),
    TEST("test", "[dependencies] or [dependencies.test]");

    private final String label;
    private final String runtimeDeclaration;

    KotlinCompilationScope(String label, String runtimeDeclaration) {
        this.label = label;
        this.runtimeDeclaration = runtimeDeclaration;
    }

    public String label() {
        return label;
    }

    public String runtimeDeclaration() {
        return runtimeDeclaration;
    }

    boolean includesRuntime(DependencyScope scope) {
        return this == MAIN
                ? scope.entersMainCompileClasspath()
                : scope.entersTestCompileClasspath();
    }
}
