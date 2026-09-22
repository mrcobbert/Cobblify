#!/usr/bin/env node
import { createHash } from "node:crypto";
import { createReadStream, realpathSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export async function verifyFileSha256(file, expected) {
  if (!/^[0-9a-f]{64}$/.test(expected)) {
    throw new Error("expected SHA-256 must be 64 lowercase hexadecimal characters");
  }

  const hash = createHash("sha256");
  await new Promise((resolve, reject) => {
    const input = createReadStream(file);
    input.on("data", (chunk) => hash.update(chunk));
    input.on("error", reject);
    input.on("end", resolve);
  });

  if (hash.digest("hex") !== expected) {
    throw new Error(`SHA-256 mismatch for ${path.basename(file)}`);
  }
}

// The CLI runs only when this file IS the entry point. The old guards compared the invoked
// path (symlink unresolved, spaces unencoded) with import.meta.url, so a symlinked or
// space-containing invocation silently ran nothing - exit 0 without verifying, or empty
// output where an ordering was expected. Compare real paths, and treat any resolution
// error as "not the entry point" so importing this module still runs nothing.
function isMainModule() {
  try {
    return (
      Boolean(process.argv[1]) &&
      realpathSync(path.resolve(process.argv[1])) === realpathSync(fileURLToPath(import.meta.url))
    );
  } catch {
    return false;
  }
}

if (isMainModule()) {
  const [file, expected] = process.argv.slice(2);
  if (!file || !expected) {
    console.error("usage: verify-file-sha256.mjs <file> <expected-sha256>");
    process.exitCode = 2;
  } else {
    try {
      await verifyFileSha256(file, expected);
      console.log(`SHA-256 verified: ${path.basename(file)}`);
    } catch (error) {
      console.error(error instanceof Error ? error.message : "SHA-256 verification failed");
      process.exitCode = 1;
    }
  }
}
