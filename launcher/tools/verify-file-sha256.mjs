#!/usr/bin/env node
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

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

const invokedPath = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : "";
if (import.meta.url === invokedPath) {
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
