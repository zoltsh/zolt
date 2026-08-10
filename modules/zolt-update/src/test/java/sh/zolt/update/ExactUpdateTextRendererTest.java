package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.UpdateClass;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ExactUpdateTextRendererTest {
    @Test
    void distinguishesDryRunAppliedAndNoOp() {
        UpdateTarget target = new UpdateTarget(
                UpdateTargetId.create("zolt.toml", OutdatedSurface.DEPENDENCY, "[dependencies]", "com.example:lib"),
                "zolt.toml",
                "zolt.lock",
                OutdatedSurface.DEPENDENCY,
                "com.example:lib",
                "[dependencies]",
                "1.0.0",
                true,
                Optional.empty(),
                List.of());
        ExactUpdatePlan changed = new ExactUpdatePlan(
                target, "1.0.0", "1.1.0", Optional.of(UpdateClass.MINOR), true, List.of());
        ExactUpdatePlan noOp = new ExactUpdatePlan(
                target, "1.0.0", "1.0.0", Optional.empty(), false, List.of());
        ExactUpdateTextRenderer renderer = new ExactUpdateTextRenderer();

        assertTrue(renderer.render(new ExactUpdateResult(changed, true, false, false, List.of()))
                .startsWith("Planned exact update (dry run):"));
        assertTrue(renderer.render(new ExactUpdateResult(changed, false, true, false, List.of("zolt.toml")))
                .startsWith("Updated:"));
        assertTrue(renderer.render(new ExactUpdateResult(noOp, false, false, false, List.of()))
                .contains("already at 1.0.0; no changes made"));
    }
}
