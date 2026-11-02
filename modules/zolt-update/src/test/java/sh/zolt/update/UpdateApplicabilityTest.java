package sh.zolt.update;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class UpdateApplicabilityTest {

    @Test
    void mutableSurfacesAreApplicable() {
        assertTrue(UpdateApplicability.isApplicable(OutdatedSurface.VERSION_ALIAS));
        assertTrue(UpdateApplicability.isApplicable(OutdatedSurface.DEPENDENCY));
        assertTrue(UpdateApplicability.isApplicable(OutdatedSurface.ANNOTATION_PROCESSOR));
        assertTrue(UpdateApplicability.isApplicable(OutdatedSurface.PLATFORM));
        assertTrue(UpdateApplicability.isApplicable(OutdatedSurface.DEPENDENCY_CONSTRAINT));
    }

    @Test
    void generatedToolLiteralsAreNotApplicableAndExplainWhy() {
        assertFalse(UpdateApplicability.isApplicable(OutdatedSurface.EXEC_TOOL_COORDINATE));
        assertFalse(UpdateApplicability.isApplicable(OutdatedSurface.KSP_TOOL));
        assertFalse(UpdateApplicability.isApplicable(OutdatedSurface.PROTOBUF_TOOL));
        assertFalse(UpdateApplicability.isApplicable(OutdatedSurface.OPENAPI_TOOL));
        assertTrue(UpdateApplicability.reason(OutdatedSurface.EXEC_TOOL_COORDINATE).contains("exec-tool"));
        assertTrue(UpdateApplicability.reason(OutdatedSurface.KSP_TOOL).contains("generated-tool"));
        assertTrue(UpdateApplicability.reason(OutdatedSurface.PROTOBUF_TOOL).contains("generated-tool"));
    }
}
