package sh.zolt.build.fingerprint;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import java.util.ArrayList;
import java.util.List;

final class BuildFingerprintSourceRoots {
    private BuildFingerprintSourceRoots() {
    }

    static List<String> main(BuildSettings settings) {
        List<String> roots = new ArrayList<>(settings.sourceRoots());
        roots.addAll(settings.generatedMainSources().stream()
                .map(GeneratedSourceStep::output)
                .toList());
        return List.copyOf(roots);
    }

    static List<String> test(BuildSettings settings) {
        List<String> roots = new ArrayList<>(settings.testSources());
        roots.addAll(settings.generatedTestSources().stream()
                .map(GeneratedSourceStep::output)
                .toList());
        roots.addAll(settings.groovyTestSources());
        return List.copyOf(roots);
    }
}
