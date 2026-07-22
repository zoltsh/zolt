import type { SmokeContext } from "smoque";

export function expectNoAnsi(t: SmokeContext, text: string, label: string): void {
  if (/\u001B\[[0-?]*[ -/]*[@-~]/u.test(text)) {
    t.fail(`${label} should not contain ANSI escape sequences.`);
  }
}
