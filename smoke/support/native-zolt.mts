import type { SmokeContext } from "smoque";

import { expectNativeExecutable, nativeImageCommand } from "./native-runtime.mts";
import { runZolt, type ZoltRuntime } from "./zolt-runtime.mts";

export async function buildNativeZolt(t: SmokeContext, bootstrap: ZoltRuntime): Promise<ZoltRuntime> {
  const command = bootstrap.root.path("apps/zolt/target/native/zolt");
  if (process.env.ZOLT_SMOKE_NATIVE_ZOLT_PREBUILT !== "1") {
    await runZolt(t, bootstrap, [
      "--no-progress", "native", "--workspace", "--member", "apps/zolt",
      "--cache-root", bootstrap.cacheRoot, "--native-image", nativeImageCommand(),
    ], { timeout: "15m" });
  }
  await expectNativeExecutable(command);
  return {
    cacheRoot: bootstrap.cacheRoot,
    command,
    env: {},
    root: bootstrap.root,
  };
}
