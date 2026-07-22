import { expect, smoke, type SmokeContext } from "smoque";

import { EXPECTED_ZOLT_COMMANDS, parseListedCommands } from "./support/command-surface.mts";
import { packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("CLI command surface smoke", { tags: ["cli", "commands"] }, async (t: SmokeContext) => {
  const zolt = await packagedZolt(t);

  await t.step("the packaged CLI exposes the intentional command set", async () => {
    const result = await runZolt(t, zolt, ["--no-progress", "--color", "never", "--list"]);
    expect.value(parseListedCommands(result.stdout)).toEqual(EXPECTED_ZOLT_COMMANDS);
  });
});
