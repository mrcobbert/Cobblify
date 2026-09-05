import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const tool = new URL("../tools/weave-agent-crypto.mjs", import.meta.url);

function run(mode, input, output, passphrase) {
  return spawnSync(process.execPath, [tool.pathname, mode, input, output], {
    env: { ...process.env, WEAVE_AGENT_PASSPHRASE: passphrase },
    encoding: "utf8",
  });
}

test("encrypted Weave resources decrypt to their original bytes", () => {
  const directory = mkdtempSync(join(tmpdir(), "cobblify-weave-crypto-"));
  try {
    const input = join(directory, "input.jar");
    const encrypted = join(directory, "input.jar.enc");
    const decrypted = join(directory, "output.jar");
    const contents = Buffer.from("not really a jar\0but still binary", "utf8");
    writeFileSync(input, contents);

    assert.equal(run("encrypt", input, encrypted, "correct horse battery staple").status, 0);
    assert.equal(run("decrypt", encrypted, decrypted, "correct horse battery staple").status, 0);
    assert.deepEqual(readFileSync(decrypted), contents);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("a wrong Weave resource password cannot produce output", () => {
  const directory = mkdtempSync(join(tmpdir(), "cobblify-weave-crypto-"));
  try {
    const input = join(directory, "input.jar");
    const encrypted = join(directory, "input.jar.enc");
    const decrypted = join(directory, "output.jar");
    writeFileSync(input, "private resource");

    assert.equal(run("encrypt", input, encrypted, "right password").status, 0);
    assert.notEqual(run("decrypt", encrypted, decrypted, "wrong password").status, 0);
    assert.throws(() => readFileSync(decrypted));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
