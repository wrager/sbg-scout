import assert from "node:assert/strict";
import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { certificateFingerprints, checkReleaseSigning, previousRelease } from "./checkReleaseSigning.mjs";

const FINGERPRINT = "ab".repeat(32);
const OTHER_FINGERPRINT = "cd".repeat(32);
const certificate = (fingerprint = FINGERPRINT, index = 1) =>
  `Signer #${index} certificate SHA-256 digest: ${fingerprint}\n`;
const release = (tag_name, overrides = {}) => ({
  tag_name, draft: false, prerelease: false,
  assets: [{ name: "sbg-scout.apk", state: "uploaded" }], ...overrides,
});

function fixture(t) {
  const temporaryRoot = mkdtempSync(join(tmpdir(), "signing-test-"));
  t.after(() => rmSync(temporaryRoot, { recursive: true, force: true }));
  const calls = [];
  const state = {
    pages: [[release("v1.2.0")]],
    outputs: [certificate(), certificate()],
    failure: undefined,
  };
  const options = {
    tag: "v1.3.0", apk: "current.apk", temporaryRoot,
    repository: "wrager/sbg-scout", apksignerJar: "apksigner.jar",
    run(command, args) {
      calls.push({ command, args });
      if (command === "java") return state.outputs.shift();
      if (state.failure) throw state.failure;
      if (args[0] === "api") return JSON.stringify(state.pages);
      assert.deepEqual(args.slice(0, 8), ["release", "download", "v1.2.0", "--repo",
        "wrager/sbg-scout", "--pattern", "sbg-scout.apk", "--dir"]);
      return "";
    },
  };
  return { state, options, calls };
}

test("selects the highest older stable version across pages, including after a publish retry", () => {
  const releases = [release("v1.1.0"), release("v1.3.0"), release("v1.10.0"),
    release("v1.2.1", { draft: true }), release("v1.2.2", { prerelease: true }),
    release("v1.2.0"), release("v0.8"), release("invalid")];
  assert.equal(previousRelease(releases, "v1.3.0").tag_name, "v1.2.0");
});

test("missing previous stable release and invalid current tags fail closed", () => {
  assert.throws(() => previousRelease([release("v1.3.0")], "v1.3.0"), /No previous/);
  assert.throws(() => previousRelease([], "v1.3.0-rc1"), /stable release tag/);
});

test("normalizes and compares the complete signer set", () => {
  assert.deepEqual(certificateFingerprints(certificate(OTHER_FINGERPRINT, 2) +
    certificate(FINGERPRINT.toUpperCase()).replaceAll("\n", "\r\n")), [FINGERPRINT, OTHER_FINGERPRINT]);
  assert.throws(() => certificateFingerprints("Verified\n"), /did not report/);
});

test("matching verified APKs pass and the temporary download is removed", (t) => {
  const { state, options, calls } = fixture(t);
  state.pages = [[release("v1.1.0")], [release("v1.2.0")]];
  assert.deepEqual(checkReleaseSigning(options), { previousTag: "v1.2.0", fingerprints: [FINGERPRINT] });
  assert.equal(calls.filter(({ command }) => command === "java").length, 2);
  assert.ok(calls.find(({ args }) => args[0] === "api").args.includes("--paginate"));
  assert.deepEqual(readdirSync(options.temporaryRoot), []);
});

test("a changed certificate blocks release and removes the download", (t) => {
  const { state, options } = fixture(t);
  state.outputs[1] = certificate(OTHER_FINGERPRINT);
  assert.throws(() => checkReleaseSigning(options), /Signing certificate differs from v1.2.0/);
  assert.deepEqual(readdirSync(options.temporaryRoot), []);
});

test("adding a signer also blocks release", (t) => {
  const { state, options } = fixture(t);
  state.outputs[0] += certificate(OTHER_FINGERPRINT, 2);
  assert.throws(() => checkReleaseSigning(options), /Signing certificate differs/);
});

test("an unsigned current APK stops before any GitHub requests", (t) => {
  const { options, calls } = fixture(t);
  options.run = (command) => { calls.push(command); throw new Error("DOES NOT VERIFY"); };
  assert.throws(() => checkReleaseSigning(options), /DOES NOT VERIFY/);
  assert.deepEqual(calls, ["java"]);
});

test("API errors cannot bypass the certificate check", (t) => {
  const { state, options } = fixture(t);
  state.failure = new Error("GitHub unavailable");
  assert.throws(() => checkReleaseSigning(options), /GitHub unavailable/);
});

for (const assets of [[], [{ name: "one.apk", state: "uploaded" }, { name: "two.apk", state: "uploaded" }],
  [{ name: "sbg-scout.apk", state: "new" }], [{ name: "../sbg-scout.apk", state: "uploaded" }]]) {
  test(`invalid previous APK metadata fails closed: ${JSON.stringify(assets)}`, (t) => {
    const { state, options } = fixture(t);
    state.pages[0][0].assets = assets;
    assert.throws(() => checkReleaseSigning(options), /exactly one APK|must be uploaded|Unexpected APK/);
  });
}

test("download errors and invalid previous APKs fail and clean up", (t) => {
  const { options } = fixture(t);
  const run = options.run;
  options.run = (command, args) => {
    if (args[0] === "release") throw new Error("Download failed");
    return run(command, args);
  };
  assert.throws(() => checkReleaseSigning(options), /Download failed/);
  assert.deepEqual(readdirSync(options.temporaryRoot), []);
});

test("an invalid previous APK cannot pass the release gate", (t) => {
  const { state, options } = fixture(t);
  state.outputs[1] = "No certificate\n";
  assert.throws(() => checkReleaseSigning(options), /did not report/);
  assert.deepEqual(readdirSync(options.temporaryRoot), []);
});
