import type { SmokeContext } from "smoque";
import { dirname, join } from "node:path";

export interface JavaCommands {
  readonly java: string;
  readonly javac: string;
}

export async function javaCommands(t: SmokeContext, minVersion = 8): Promise<JavaCommands> {
  const java = await t.tools.java({ minVersion });
  if (java.path === undefined) {
    return { java: java.command, javac: "javac" };
  }
  return { java: java.command, javac: join(dirname(java.path), "javac") };
}
