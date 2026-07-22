import { smoke, type SmokeContext } from "smoque";

import {
  FRAMEWORK_NATIVE_DIAGNOSTIC_CONTRACTS,
  verifyFrameworkNativeDiagnostic,
} from "../../../smoke/support/framework-native-diagnostics.mts";
import { packagedZolt } from "../../../smoke/support/zolt-smoke.mts";

smoke.suite("framework native diagnostics smoke", { tags: ["native", "diagnostics", "framework"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-framework-native-diagnostics");
  const zolt = await packagedZolt(t);

  for (const contract of FRAMEWORK_NATIVE_DIAGNOSTIC_CONTRACTS) {
    await t.step(`${contract.framework} fails before invoking Native Image`, async () => {
      await verifyFrameworkNativeDiagnostic(t, zolt, (relativePath) => work.path(relativePath), contract);
    });
  }
});
