import { createCipheriv, createDecipheriv, randomBytes, scryptSync } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

const MAGIC = Buffer.from("CBLW1");
const SALT_BYTES = 16;
const IV_BYTES = 12;
const TAG_BYTES = 16;

function usage() {
  throw new Error("usage: weave-agent-crypto.mjs <encrypt|decrypt> <input> <output>");
}

const [mode, inputPath, outputPath] = process.argv.slice(2);
if (!mode || !inputPath || !outputPath || !["encrypt", "decrypt"].includes(mode)) {
  usage();
}

const passphrase = process.env.WEAVE_AGENT_PASSPHRASE;
if (!passphrase) {
  throw new Error("WEAVE_AGENT_PASSPHRASE is required");
}

if (mode === "encrypt") {
  const salt = randomBytes(SALT_BYTES);
  const iv = randomBytes(IV_BYTES);
  const key = scryptSync(passphrase, salt, 32);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const ciphertext = Buffer.concat([cipher.update(readFileSync(inputPath)), cipher.final()]);
  const tag = cipher.getAuthTag();
  writeFileSync(outputPath, Buffer.concat([MAGIC, salt, iv, tag, ciphertext]));
} else {
  const encrypted = readFileSync(inputPath);
  const headerBytes = MAGIC.length + SALT_BYTES + IV_BYTES + TAG_BYTES;
  if (encrypted.length <= headerBytes || !encrypted.subarray(0, MAGIC.length).equals(MAGIC)) {
    throw new Error("invalid encrypted Weave agent");
  }

  let offset = MAGIC.length;
  const salt = encrypted.subarray(offset, offset += SALT_BYTES);
  const iv = encrypted.subarray(offset, offset += IV_BYTES);
  const tag = encrypted.subarray(offset, offset += TAG_BYTES);
  const key = scryptSync(passphrase, salt, 32);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);
  writeFileSync(outputPath, Buffer.concat([decipher.update(encrypted.subarray(offset)), decipher.final()]));
}
