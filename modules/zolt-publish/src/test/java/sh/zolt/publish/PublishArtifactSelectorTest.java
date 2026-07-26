package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.PackageMode;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PublishArtifactSelectorTest {
    @Test
    void rejectsGenericMainForQuarkusFastJarLayout() {
        PublishException exception = assertThrows(
                PublishException.class,
                () -> PublishArtifactSelector.select(
                        List.of("main"),
                        PackageMode.QUARKUS));

        assertTrue(exception.getMessage().contains(
                "multi-file runtime layout"));
        assertTrue(exception.getMessage().contains(
                "cannot be published as a single Maven main artifact"));
    }
}
