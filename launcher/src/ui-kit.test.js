import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { readFileSync } from "node:fs";

import { accentVars, contrast, shade, snap, units, wordmark } from "./ui/kit.js";

test("snap lands on whole device pixels and never below one", () => {
  assert.equal(snap(2, 1), 2);
  assert.equal(snap(2, 2), 2);
  assert.equal(snap(2, 1.25), 2.4); // round(2.5) = 3 device px
  assert.equal(snap(2, 1.5), 2);
  assert.equal(snap(0.1, 1), 1);
});

test("units sets --su and --fpx for the window's scale", () => {
  const dom = new JSDOM("<!doctype html><html></html>");
  Object.defineProperty(dom.window, "devicePixelRatio", { value: 1.25 });
  units(dom.window);
  const s = dom.window.document.documentElement.style;
  assert.equal(s.getPropertyValue("--su"), "2.4px");
  assert.equal(s.getPropertyValue("--fpx"), "2.4px");
});

test("tokens.css's default accent block is exactly what setAccent writes for Pale Blue", () => {
  // The prototype boots with setAccent("Pale Blue"), so these are the values it renders; the stylesheet only
  // covers the moment before JS runs and must not differ from them.
  const css = readFileSync(new URL("./ui/tokens.css", import.meta.url), "utf8");
  const v = accentVars("#C0DCFF");
  for (const [k, want] of Object.entries(v)) {
    const m = css.match(new RegExp(k + ":\\s*(#[0-9A-Fa-f]{6})"));
    assert.ok(m, k);
    assert.equal(m[1].toLowerCase(), want.toLowerCase(), k);
  }
  assert.equal(v["--acc-rim"], "#e4fdff");
  assert.equal(shade("#808080", 0), "#808080");
});

test("dark accents get light ink", () => {
  assert.equal(accentVars("#017922")["--acc-on"], "#EDEDED");
  assert.ok(contrast("#000000", "#FFFFFF") > 20);
});

test("the wordmark is crisp SVG at whole device pixels", () => {
  const svg = wordmark(2, { font: "minecraft" }, 2);
  assert.match(svg, /width="64" height="11"/);
  assert.match(svg, /fill="#FFFFFF"/);
  assert.match(svg, /translate\(1 1\)/);
  assert.doesNotMatch(wordmark(3, { shadow: false }, 1), /translate/);
});
