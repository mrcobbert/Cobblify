import { createHash, createPublicKey, verify } from "node:crypto";

/**
 * Test-side minisign verification, so a tool test can prove a signature the release tooling
 * produced really verifies under the public key - the same check the Rust updater performs
 * with `minisign-verify`. Inputs are in the exact shapes Tauri passes around: the public key
 * as `tauri signer generate` writes it (base64 of the two-line minisign text) and the
 * signature as `<file>.sig` holds it (base64 of the four-line minisign text).
 *
 * @returns {{ keyIdMatches: boolean, signatureValid: boolean, globalValid: boolean, prehashed: boolean }}
 */
export function verifyMinisign(publicKeyFileText, signatureFileText, message) {
  const pubLines = Buffer.from(publicKeyFileText.trim(), "base64").toString("utf8").split("\n");
  const pub = Buffer.from(pubLines[1], "base64");
  if (pub.length !== 42 || pub.subarray(0, 2).toString() !== "Ed") throw new Error("not a minisign public key");
  const keyId = pub.subarray(2, 10);
  const spki = Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), pub.subarray(10, 42)]);
  const key = createPublicKey({ key: spki, format: "der", type: "spki" });

  const sigLines = Buffer.from(signatureFileText.trim(), "base64").toString("utf8").split("\n");
  const sig = Buffer.from(sigLines[1], "base64");
  if (sig.length !== 74) throw new Error("not a minisign signature");
  const prehashed = sig.subarray(0, 2).toString() === "ED";
  const signature = sig.subarray(10, 74);
  const trustedComment = sigLines[2].replace(/^trusted comment: /, "");
  const globalSignature = Buffer.from(sigLines[3], "base64");
  const data = prehashed ? createHash("blake2b512").update(message).digest() : Buffer.from(message);
  return {
    keyIdMatches: sig.subarray(2, 10).equals(keyId),
    signatureValid: verify(null, data, key, signature),
    globalValid: verify(null, Buffer.concat([signature, Buffer.from(trustedComment, "utf8")]), key, globalSignature),
    prehashed,
  };
}
