/**
 * The 0.18 launcher's home window, ported from the prototype (proto/servers.js): the bare header with the wordmark
 * and the gear, one left column with the Launch split key and its client menu, the 3D island free on the right,
 * the settings sheet, What's New and the release-notes history.
 *
 * It owns the DOM and the layout passes that keep every edge on whole device pixels; main.js owns the backend and
 * hands it a model (ui/model.js) plus the actions below. The prototype's demo, playground, QA hooks and mock window
 * frame are not part of it.
 *
 * actions: play(id), cancel(), select(id), add(addDescriptor), setup(action), pick(candidateId), cancelAdd(),
 *          repairPref(), update(action), setOverlay(on), setAutoUpdate(on), setDev(on), repairLunar()
 */
import { shade, wordmark } from "./kit.js";
import { ART, CL, T, esc, pix, pixSh, quarter, trimArt } from "./art.js";
import { channelOf, fmtDate, noteFor, whatsNewItems } from "./notes.js";
import NOTES from "../release-notes.json";
import * as I3 from "./island3d.js";
import fallbackSrc from "./island-fallback.png";

/* the island opens facing its front: the house's entrance square on, the lawn and the bed before it */
const FRONT = 270;
/* the component's box keeps one block (6 art px) of headroom on top, for a stuck part hanging there */
const HEAD = 6;

export function createApp(app, actions) {
  const $ = (s, r = app) => r.querySelector(s);
  const doc = app.ownerDocument, win = doc.defaultView;
  const root = doc.documentElement;
  const dpr = () => win.devicePixelRatio || 1;
  const suPx = () => parseFloat(win.getComputedStyle(root).getPropertyValue("--su")) || 2;
  const isMC = () => root.dataset.font !== "modern";
  const reduced = () => !!win.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  app.dataset.platform = /Mac/i.test(win.navigator.platform || win.navigator.userAgent || "") ? "mac" : "win";

  let H = null; // home model
  let S = null; // sheet model
  let version = "", devTag = false;
  let view = "home";
  let menuOpen = false, sheetOpen = false, modal = null, histSel = NOTES[0]?.version ?? null;
  let active = true;

  app.innerHTML = `<div id="main"></div><aside class="sheet" id="sheet" role="dialog" aria-label="Settings"></aside><div id="layer"></div><div id="istage" aria-hidden="true"></div>`;

  /* ---------- header ---------- */
  const updReady = () => !!H?.updReady;
  const gearKey = () =>
    `<button class="key gear" data-act="settings" aria-haspopup="dialog" aria-controls="sheet" aria-expanded="${sheetOpen}" aria-label="${updReady() ? "Settings, an update is ready" : "Settings"}" title="Settings">${pix(ART.gear, "var(--hi)")}${updReady() ? '<span class="gdot" aria-hidden="true"></span>' : ""}</button>`;
  function headerHTML() {
    if (view === "history") {
      return `<header class="hdr" data-tauri-drag-region><button class="key back" data-act="back" aria-label="Back">${pix(ART.back, "var(--hi)")}</button>${T("Release notes", "sh")}<span class="grow"></span></header>`;
    }
    const k = Math.max(1, Math.round(suPx() * dpr()));
    return `<header class="hdr bare" data-tauri-drag-region>
      <div class="brand"><span class="wm">${wordmark(k, { font: isMC() ? "minecraft" : "modern" }, dpr())}</span><button class="ver${devTag ? " dev" : ""}" data-act="history" title="Release notes">${esc(version)}</button>${devTag ? `<span class="tag dev">${T("Dev builds")}</span>` : ""}</div>
      <span class="grow"></span>${gearKey()}
    </header>`;
  }

  /* ---------- the Launch key ---------- */
  /* The ink on an accent hero. The label is --acc-on, except that Minecraft's font draws light ink in the game's own
   * white with vanilla's shadow (the colour at a quarter, 1 font px down-right), and dark ink on a pale accent
   * without one. The client art gets a second ink for its '+' pixels: the accent at the OKLab lightness halfway to the
   * ink, so a prism keeps both its faces on every accent. */
  const okL = (hex) => {
    const n = parseInt(hex.slice(1), 16), f = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
    const r = f((n >> 16) & 255), g = f((n >> 8) & 255), b = f(n & 255);
    return 0.2104542553 * Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b) + 0.793617785 * Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
      - 0.0040720468 * Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  };
  const accentHex = () => win.getComputedStyle(root).getPropertyValue("--acc").trim() || "#C0DCFF";
  function heroInk() {
    const st = win.getComputedStyle(root);
    const acc = accentHex(), on = (st.getPropertyValue("--acc-on").trim() || "#121212").toUpperCase();
    const light = on !== "#121212", shadow = light && isMC();
    const ink = shadow ? "#FFFFFF" : on, ink2 = shade(acc, (okL(ink) - okL(acc)) / 2, light ? 0.9 : 0.8).toUpperCase();
    return `--ink:${ink};--ink2:${ink2};--ink-sh:${shadow ? quarter(ink) : "transparent"};--ink2-sh:${shadow ? quarter(ink2) : "transparent"}`;
  }

  /* "Launch Cobblify" (the user, 2026-10-04); "Launch" only if a window is too narrow. The client's icon says which. */
  const PLAY_LABELS = ["Launch Cobblify", "Launch"];
  function heroHTML(hd) {
    const ink = heroInk();
    if (hd.kind === "prog") {
      return `<button class="key acc hero prog static" style="${ink}" data-pct="${hd.pct}" aria-disabled="true" aria-label="${esc(hd.label)}, ${hd.pct} percent">
        <span class="fill"></span><span class="lab">${T(hd.label)}</span><span class="lab on" aria-hidden="true">${T(hd.label)}</span></button>`;
    }
    if (hd.kind === "wait") return `<button class="key hero prog wait static" aria-disabled="true"><span class="lab">${T(hd.label, "sh")}</span></button>`;
    if (hd.kind === "play") {
      const cl = CL[H.sel], labs = PLAY_LABELS.map((s, i) => `<span class="t pl${i ? "" : " cur"}">${esc(s)}</span>`).join("");
      return `<div class="split" style="${ink}"><button class="key acc hero play" data-act="play" aria-label="Launch Cobblify in ${esc(cl.name)}"><span class="pi">${pixSh(trimArt(cl.art), "var(--ink)", "var(--ink2)", "var(--ink-sh)", "var(--ink2-sh)")}</span><span class="pls">${labs}</span><span class="psp" aria-hidden="true"></span></button><button class="key acc caret" data-act="menu" aria-haspopup="menu" aria-controls="client-menu" aria-expanded="${menuOpen}" aria-label="Choose a client" title="Choose a client">${pixSh(ART.caret, "var(--ink)", null, "var(--ink-sh)")}</button></div>`;
    }
    return `<button class="key acc hero" style="${ink}" data-act="${hd.act}">${T(hd.label)}</button>`;
  }

  function leadHTML(lead) {
    if (!lead || lead.kind !== "choose") return "";
    return `<div class="choices" role="radiogroup" aria-label="Prism instances">${lead.candidates
      .map((c) => `<button class="choice${c.id === lead.picked ? " on" : ""}" role="radio" aria-checked="${c.id === lead.picked}" data-act="pick" data-id="${esc(c.id)}"><span class="cx">${T(c.name, "sh")}</span><span class="ck">${c.id === lead.picked ? pix(ART.check, "var(--acc)") : ""}</span></button>`)
      .join("")}</div>`;
  }

  /* Home's left side is the button and nothing else: the button says what to do; progress and waits are its face;
   * a second key (Cancel) and Prism's instance choice are the only other controls. What a status line would say is
   * read out to screen readers. With nothing set up the key stands alone in the middle and the island waits. */
  function homeHTML() {
    const lead = leadHTML(H.lead);
    const side = (H.keys || []).map((x, i) => `<button class="key" data-act="${x.act}" data-k="${i}">${T(x.label, "sh")}</button>`).join("");
    return `<div class="body home${H.solo ? " solo" : ""}">
      <section class="col" aria-label="Play"><div class="grp${lead ? " lit" : ""}"><p class="sr" role="status" aria-live="polite">${esc(H.said || "")}</p>${lead ? `<div class="lead">${lead}</div>` : ""}
        <div class="go"><div class="heros">${heroHTML(H.hero)}</div>${side ? `<div class="keys2">${side}</div>` : ""}</div></div></section>
      <div class="art" id="art"><img id="isl" alt="" src="${fallbackSrc}"></div>
    </div>`;
  }

  /* ---------- the client menu (Launch's ▾) ---------- */
  function menuHTML() {
    if (!menuOpen || !H || view !== "home" || H.solo) return "";
    const art = (id) => pixSh(CL[id].art, "var(--hi)", "var(--mid)", "var(--ish1,transparent)", "var(--ish2,transparent)");
    const dlMark = () => pixSh(ART.dl, "var(--mid)", null, "var(--ish1,transparent)");
    const rows = H.clients.map((id) => `<button class="mrow" role="menuitemradio" tabindex="-1" aria-checked="${id === H.sel}" data-act="sel" data-id="${id}"><span class="tile">${art(id)}</span>${T(CL[id].name, "sh grow")}<span class="ck">${id === H.sel ? pixSh(ART.check, "var(--acc)", null, "var(--ish-ck,transparent)") : ""}</span></button>`).join("");
    const a = H.add;
    const add = a ? `<div class="mdiv" role="separator"></div><button class="mrow madd" role="menuitem" tabindex="-1" data-act="add-client"${a.installed ? "" : ` aria-label="Add ${esc(CL[a.id].short)}, download"`}><span class="tile add">${pixSh(ART.plus, "currentColor", null, "var(--ish1,transparent)")}</span>${T("Add " + CL[a.id].short, "grow")}<span class="ck">${a.installed ? "" : dlMark()}</span></button>` : "";
    return `<div class="menu" id="client-menu" role="menu" tabindex="-1" aria-label="Clients" style="--acc-q:${quarter(accentHex())}">${rows}${add}</div>`;
  }
  const menuKey = () => $(".caret");
  /* The menu sits on the Launch half's left edge, exactly as wide, 2 su below the split; it opens upward only if it
   * can't fit below and there is more room above, never into the header row. Whole su, device-pixel snapped. */
  function placeMenu() {
    fitPlay();
    const m = $("#layer .menu"), k = menuKey();
    if (!m) return;
    if (!k) { menuOpen = false; m.remove(); return; }
    const a = app.getBoundingClientRect(), r = $(".split").getBoundingClientRect(), hb = $(".split .hero").getBoundingClientRect(), d = dpr(), su = suPx(), snap = (v) => Math.round(v * d) / d;
    const Hh = app.clientHeight, hdr = $(".hdr");
    Object.assign(m.style, { maxHeight: "", width: snap(hb.width) + "px" });
    const top = Math.max(su, hdr ? hdr.getBoundingClientRect().bottom - a.top : 0), gap = 2 * su, sTop = r.top - a.top, sBot = r.bottom - a.top;
    const roomUp = Math.floor((sTop - gap - top) / su) * su, roomDown = Math.floor((Hh - su - sBot - gap) / su) * su;
    let h = m.getBoundingClientRect().height;
    const up = h > roomDown && roomUp > roomDown;
    if (h > (up ? roomUp : roomDown)) { h = Math.max(4 * su, up ? roomUp : roomDown); m.style.maxHeight = h + "px"; }
    m.style.left = snap(hb.left - a.left) + "px";
    m.style.top = snap(up ? sTop - gap - h : sBot + gap) + "px";
    nudge(m);
  }
  function toggleMenu(open, which) {
    menuOpen = open == null ? !menuOpen : open;
    const l = $("#layer"), was = l.querySelector(".menu"), inside = !!was && was.contains(doc.activeElement);
    l.querySelectorAll(".menu").forEach((x) => x.remove());
    const k = menuKey();
    if (k) k.setAttribute("aria-expanded", String(menuOpen));
    if (!menuOpen) { if (inside && k) k.focus({ preventScroll: true }); return; }
    l.insertAdjacentHTML("beforeend", menuHTML());
    placeMenu();
    const rows = [...l.querySelectorAll(".menu .mrow")];
    const f = which === "first" ? rows[0] : which === "last" ? rows[rows.length - 1] : rows.find((x) => x.getAttribute("aria-checked") === "true") || rows[0];
    if (f) { if (which) f.closest(".menu").classList.add("kb"); f.focus({ preventScroll: true }); }
  }
  /* WAI-ARIA menu button: on the ▾, Down/Up open it on the first/last row; in it, Up/Down move and wrap, Home/End
   * jump, a letter jumps to the next client starting with it, Enter/Space choose, Tab closes. Esc is the global
   * handler's first step. */
  function menuKeys(e) {
    if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || !H || view !== "home") return;
    const onCaret = !!e.target.closest?.(".caret");
    if (!menuOpen) {
      if (onCaret && (e.key === "ArrowDown" || e.key === "ArrowUp")) { e.preventDefault(); toggleMenu(true, e.key === "ArrowUp" ? "last" : "first"); }
      return;
    }
    const m = $("#layer .menu");
    if (!m || !(m.contains(e.target) || onCaret)) return;
    const rows = [...m.querySelectorAll(".mrow")], n = rows.length, i = rows.indexOf(doc.activeElement);
    let to = null;
    if (e.key === "ArrowDown") to = rows[i < 0 ? 0 : (i + 1) % n];
    else if (e.key === "ArrowUp") to = rows[i < 0 ? n - 1 : (i - 1 + n) % n];
    else if (e.key === "Home" || e.key === "PageUp") to = rows[0];
    else if (e.key === "End" || e.key === "PageDown") to = rows[n - 1];
    else if (e.key === "Tab") { toggleMenu(false); const k = menuKey(); k?.focus({ preventScroll: true }); return; }
    else if (e.key.length === 1 && /\S/.test(e.key)) {
      const ch = e.key.toLowerCase(), order = rows.slice(i + 1).concat(rows.slice(0, i + 1));
      to = order.find((x) => x.dataset.act === "sel" && x.textContent.trim().toLowerCase().startsWith(ch)) || null;
      if (!to) return;
    }
    if (!to) return;
    e.preventDefault();
    m.classList.add("kb");
    to.focus({ preventScroll: true });
  }
  app.addEventListener("keydown", menuKeys);
  /* the pointer and the keys share one current row */
  app.addEventListener("mousemove", (e) => {
    const m = menuOpen && e.target.closest?.("#layer .menu");
    if (!m) return;
    m.classList.remove("kb");
    const r = e.target.closest(".mrow");
    if (r && doc.activeElement !== r) r.focus({ preventScroll: true });
  });
  /* focus leaving the menu (and not for the ▾, whose click toggles it) closes it */
  app.addEventListener("focusout", (e) => {
    if (!menuOpen || !e.target.closest?.("#layer .menu")) return;
    win.setTimeout(() => {
      const f = doc.activeElement;
      if (menuOpen && !(f && f.closest && (f.closest("#layer .menu") || f.closest(".caret")))) toggleMenu(false);
    }, 0);
  });

  /* ---------- release history: a version list beside one reading column ---------- */
  function historyHTML() {
    const rows = NOTES.map((n) => `<button class="row${n.version === histSel ? " sel" : ""}" data-act="jump" data-v="${esc(n.version)}"><span class="txt">${T(n.version, "sh")}${T(fmtDate(n.date, true), "mid")}</span></button>`).join("");
    const rels = NOTES.map((n) => `<article class="rel" id="rel-${n.version.replace(/\./g, "_")}">
      <div class="rel-h">${T(n.version, "sh nm")}<span class="tag${n.channel === "dev" ? " dev" : ""}">${T(n.channel === "dev" ? "Dev build" : "Stable")}</span>${T(fmtDate(n.date), "mid")}</div>
      ${n.sections.map(([h, items]) => `<h4>${T(h)}</h4><ul>${items.map((i) => `<li>${esc(i)}</li>`).join("")}</ul>`).join("")}</article>`).join("");
    return `<div class="body"><aside class="master hist-list" aria-label="Releases"><div class="mh">${T(`Releases (${NOTES.length})`, "sh")}</div><div class="div"></div>${rows}</aside>
      <div class="reading" id="reading"><div class="rcol">${rels}</div></div></div>`;
  }

  /* ---------- settings sheet (the in-game RShift sheet) ---------- */
  const row = (id, label, ctl, extra = "") => `<div class="srow" data-row="${id}"><div class="lbl">${T(label, "sh")}${extra}</div><div class="ctl">${ctl}</div></div>`;
  const tog = (key, label, st) => `<button class="tog" role="switch" aria-checked="${!!st.on}" aria-label="${esc(label)}" data-act="tog" data-key="${key}"${st.disabled ? " disabled" : ""}><span class="tf"></span><span class="ti"></span><span class="to"></span><span class="tk"></span></button>`;
  const keyBtn = (k, extra = "") => `<button class="key" data-act="${k.act}"${k.action ? ` data-action="${esc(k.action)}"` : ""}${extra}${k.disabled ? " disabled" : ""}>${T(k.label, "sh")}</button>`;
  function sheetRows() {
    const s = S;
    const why = s.reason ? `<p class="t ml lo reason">${esc(s.reason)}</p>` : "";
    const verRow = row("version", s.version.label, s.version.key ? keyBtn(s.version.key) : "", `<span class="t mid st">${esc(s.version.word)}</span>`);
    const clients = [
      s.lunarRepair ? row("lunar", "Lunar Client", keyBtn({ label: "Repair", act: "repair-lunar", disabled: s.locked })) : "",
      s.prismAdd ? row("prism", "Prism Launcher", keyBtn({ label: "Add", act: "sheet-add", disabled: s.locked })) : "",
    ].join("");
    return `<section class="sec">${why}${row("overlay", "External Overlay", tog("overlay", "External Overlay", s.overlay))}</section>
      <section class="sec">${row("auto", "Auto-update", tog("auto", "Auto-update", s.autoUpdate))}${row("dev", "Dev builds", tog("dev", "Dev builds", s.dev))}${verRow}</section>
      ${clients ? `<section class="sec">${clients}</section>` : ""}
      <p class="read disclaim">Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.</p>`;
  }
  function sheetHTML() {
    return `<div class="sh-hdr">${T("Settings", "sh nm")}<span class="grow"></span><button class="key xkey" data-act="close" aria-label="Close settings">${pix(ART.x, "var(--hi)")}</button></div><div class="sh-body">${sheetRows()}</div>`;
  }
  /* settings change in place: a switch keeps its element (and its 90 ms motion) when only its state changed */
  function patchSheet() {
    const sh = $("#sheet"), body = sh.querySelector(".sh-body");
    if (!body) { sh.innerHTML = sheetHTML(); nudge(sh); return; }
    const tmp = doc.createElement("div");
    tmp.innerHTML = sheetRows();
    const same = tmp.querySelectorAll("[data-row]").length === body.querySelectorAll("[data-row]").length
      && [...tmp.querySelectorAll("[data-row]")].every((r, i) => r.dataset.row === body.querySelectorAll("[data-row]")[i].dataset.row);
    if (!same) {
      const f = doc.activeElement && body.contains(doc.activeElement) ? doc.activeElement.dataset.key || doc.activeElement.dataset.act : null;
      body.innerHTML = tmp.innerHTML;
      if (f) body.querySelector(`[data-key="${f}"],[data-act="${f}"]`)?.focus({ preventScroll: true });
    } else {
      tmp.querySelectorAll("[data-row]").forEach((r) => {
        const old = body.querySelector(`[data-row="${r.dataset.row}"]`);
        const nt = r.querySelector(".tog"), ot = old.querySelector(".tog");
        if (nt && ot) {
          ot.setAttribute("aria-checked", nt.getAttribute("aria-checked"));
          ot.disabled = nt.disabled;
        } else if (old.innerHTML !== r.innerHTML) {
          const hadFocus = old.contains(doc.activeElement);
          old.innerHTML = r.innerHTML;
          if (hadFocus) old.querySelector("button")?.focus({ preventScroll: true });
        }
      });
      const oldWhy = body.querySelector(".reason"), newWhy = tmp.querySelector(".reason");
      if (!!oldWhy !== !!newWhy) body.querySelector(".sec").innerHTML = tmp.querySelector(".sec").innerHTML;
    }
    nudge(sh);
  }
  function openSheet() {
    sheetOpen = true;
    toggleMenu(false);
    $(".hdr .gear")?.setAttribute("aria-expanded", "true");
    const sh = $("#sheet");
    sh.innerHTML = sheetHTML();
    void sh.offsetWidth;
    sh.classList.add("open");
    nudge(sh);
    win.setTimeout(() => $("#sheet .xkey")?.focus({ preventScroll: true }), 150);
  }
  function closeSheet() {
    sheetOpen = false;
    $(".hdr .gear")?.setAttribute("aria-expanded", "false");
    $("#sheet").classList.remove("open");
    $('.hdr [data-act="settings"]')?.focus({ preventScroll: true });
  }

  /* ---------- What's New: Ore's update pop-up ---------- */
  function modalHTML() {
    if (modal !== "whatsnew") return "";
    const n = noteFor(NOTES, version);
    if (!n) return "";
    const title = `What's new in ${n.version}`;
    return `<div class="scrim" data-act="close-modal"></div>
      <div class="modal" role="dialog" aria-modal="true" aria-label="${esc(title)}" data-center>
        <div class="sh-hdr">${T(title, "sh nm")}<span class="grow"></span><button class="key xkey" data-act="close-modal" aria-label="Close">${pix(ART.x, "var(--hi)")}</button></div>
        <div class="wn">${T(`${fmtDate(n.date)} · ${channelOf(version)}`, "mid")}<ul>${whatsNewItems(n).map((i) => `<li>${esc(i)}</li>`).join("")}</ul>
          <button class="lnk" data-act="history">${T("All release notes")}</button></div>
        <div class="mfoot-band"><button class="key acc" data-act="close-modal">${T("Continue")}</button></div>
      </div>`;
  }
  function openWhatsNew() {
    if (!noteFor(NOTES, version)) return;
    modal = "whatsnew";
    render();
    win.setTimeout(() => $(".modal .mfoot-band .key")?.focus({ preventScroll: true }), 0);
  }
  function closeModal() {
    modal = null;
    $("#layer").querySelectorAll(".scrim,.modal").forEach((x) => x.remove());
    ($(".hero:not([aria-disabled])") || $('.hdr [data-act="settings"]'))?.focus({ preventScroll: true });
  }

  /* ---------- render ---------- */
  function focusSig() {
    const f = doc.activeElement;
    if (!f || f === doc.body || !app.contains(f) || $("#sheet").contains(f)) return null;
    return { act: f.dataset.act, id: f.dataset.id, v: f.dataset.v, hero: f.classList.contains("hero") };
  }
  function restoreFocus(sig) {
    if (!sig) return;
    let el = null;
    if (sig.act) el = [...app.querySelectorAll(`#main [data-act="${sig.act}"], #layer [data-act="${sig.act}"]`)].find((e) => (sig.id == null || e.dataset.id === sig.id) && (sig.v == null || e.dataset.v === sig.v));
    if (!el && (sig.hero || ["cancel", "play", "sel", "setup"].includes(sig.act))) el = $(".hero");
    el?.focus({ preventScroll: true });
  }
  function render() {
    if (!H) return;
    const sig = focusSig();
    const was = $(".body.home.solo .col .heros"), from = was && was.getBoundingClientRect().left;
    $("#main").innerHTML = headerHTML() + (view === "history" ? historyHTML() : homeHTML());
    const keepMenu = menuOpen && view === "home" && !H.solo && !H.launching;
    menuOpen = keepMenu;
    $("#layer").innerHTML = modalHTML() + menuHTML();
    if (sheetOpen && S) patchSheet();
    after();
    if (was && !H.solo) slideCol(from);
    restoreFocus(sig);
  }
  /* the column leaves the middle for the left (FLIP: drawn at its new place, moved back, eased home) */
  function slideCol(from) {
    const col = $(".body.home .col"), h = col?.querySelector(".heros");
    if (!h || !col.animate) return;
    const dx = from - h.getBoundingClientRect().left;
    if (Math.abs(dx) < 1 || reduced()) return;
    col.animate([{ transform: `translateX(${dx}px)` }, { transform: "none" }], { duration: 420, easing: "cubic-bezier(.2,.7,.2,1)" });
  }
  function after() {
    const accOn = win.getComputedStyle(root).getPropertyValue("--acc-on").trim().toUpperCase();
    app.style.setProperty("--acc-tsh", accOn === "#EDEDED" ? "#3B3B3B" : "transparent");
    const sc = $(".body.home.solo .col");
    if (sc) { const d = dpr(); sc.style.marginLeft = Math.round(((sc.parentNode.clientWidth - sc.offsetWidth) / 2) * d) / d + "px"; }
    app.classList.toggle("short", app.clientHeight < 500);
    snapPct(); placeIsland(); centerAll(); snapHero(); nudge(); placeMenu();
  }
  /* Launch's row on whole device pixels: centring can leave it on half a pixel, which would soften the bevel */
  function snapHero() {
    const g = $(".col .grp"), h = $(".col .heros");
    if (!g || !h) return;
    g.style.top = "";
    const d = dpr(), t = h.getBoundingClientRect().top * d, dy = (Math.round(t) - t) / d;
    if (Math.abs(dy) > 0.001) g.style.top = dy + "px";
  }
  /* whole-su progress fills */
  function snapPct() {
    const su = suPx();
    app.querySelectorAll("[data-pct]").forEach((el) => {
      const w = el.clientWidth, pw = Math.round(((Number(el.dataset.pct) / 100) * w) / su) * su;
      el.style.setProperty("--pw", Math.min(w, pw) + "px");
    });
  }
  function centerAll() {
    const d = dpr();
    app.querySelectorAll("[data-center]").forEach((el) => {
      const p = el.offsetParent || app, w = el.offsetWidth, h = el.offsetHeight;
      el.style.left = Math.round(((p.clientWidth - w) / 2) * d) / d + "px";
      el.style.top = Math.max(0, Math.round(((p.clientHeight - h) / 2) * d) / d) + "px";
    });
  }
  /* The longest Launch label that fits beside the art, then the label nudged on its new position. */
  function fitPlay() {
    const h = $(".hero.play");
    if (!h) return;
    const labs = [...h.querySelectorAll(".pl")], pi = h.querySelector(".pi"), cs = win.getComputedStyle(h);
    const gap = parseFloat(cs.columnGap) || 0, inner = h.clientWidth - parseFloat(cs.paddingLeft || 0) - parseFloat(cs.paddingRight || 0);
    const room = inner - (pi ? 2 * (pi.offsetWidth + gap) : 0);
    const pick = labs.find((l) => l.getBoundingClientRect().width <= room + 0.01) || labs[labs.length - 1];
    labs.forEach((l) => l.classList.toggle("cur", l === pick));
    nudge(h);
  }
  /* Minecraft text: first glyph column and baseline on device pixels. The stand-in font has a half-pixel left
   * bearing at 1x (one whole font px at 2x); runs once per layout, never per data tick. */
  function nudge(scope = app) {
    const els = [...scope.querySelectorAll(".t")];
    els.forEach((e) => { e.style.left = ""; e.style.top = ""; });
    if (!isMC() || !doc.createRange) return;
    const d = dpr(), out = [];
    for (const e of els) {
      const r = doc.createRange();
      r.selectNodeContents(e);
      const rr = [...(r.getClientRects?.() ?? [])].find((q) => q.width > 0);
      if (!rr) continue;
      const bearing = (e.classList.contains("x2") ? 1 : 0.5) * suPx() * d;
      const x = rr.left * d + bearing, y = rr.top * d;
      out.push([e, (Math.round(x) - x) / d, (Math.round(y) - y) / d]);
    }
    out.forEach(([e, dx, dy]) => { if (Math.abs(dx) > 0.001) e.style.left = dx + "px"; if (Math.abs(dy) > 0.001) e.style.top = dy + "px"; });
  }

  /* ---------- the island ---------- */
  let isl3 = null, islHost = null, isl3failed = false, fitCache = null, openBuild = null, introTok = 0;
  function islandTarget() {
    const t = { built: 1, buildStyle: "partdrop", stuck: false, bed: true, wool: accentHex(), gen: false, ingots: 0, grey: false };
    if (H.solo) { t.built = 0; t.bed = false; return t; }
    if (openBuild) { t.built = openBuild.k / 6; t.bed = openBuild.k >= 6; }
    return t;
  }
  function island3d() {
    if (isl3 || isl3failed) return isl3;
    islHost = doc.createElement("div");
    islHost.className = "isl3d";
    islHost.setAttribute("aria-hidden", "true");
    try {
      /* artAA false: art edges stay pixel-crisp while you drag it round (the user, 2026-10-04) */
      isl3 = I3.mount(islHost, { edges: "art", rest: "quarter", motion: "drag", light: "smooth", stage: $("#istage"), artAA: false, yaw0: FRONT });
    } catch (e) {
      isl3 = null;
    }
    if (!isl3) isl3failed = true;
    return isl3;
  }
  function placeIsland() {
    const art = $("#art");
    if (!art) { if (isl3) isl3.setActive(false); return; }
    const i3 = view === "home" ? island3d() : null;
    if (!i3) { fitIsland(art); return; }
    const img = $("#isl");
    if (img) img.style.display = "none";
    placeHome(i3, art);
    i3.setActive(active && !H.solo);
  }
  /* The slot is the whole window, header row included (the home header is bare), behind the column. Scales stay
   * whole (device px per art px), so the island keeps the game's pixel grid; at 900x520 it is 2x. Placed by the
   * island as drawn at rest: the same margin above, below and to the right. */
  function placeHome(i3, art) {
    const d = dpr(), su = suPx(), ar = app.getBoundingClientRect(), hdr = $(".hdr"), col = $(".col");
    const hh = hdr ? hdr.getBoundingClientRect().bottom - ar.top : 0, colR = col ? col.getBoundingClientRect().right - ar.left : 0;
    const W = app.clientWidth, Ht = app.clientHeight, pad = 8 * su;
    const bx = I3.boxSize(6);
    const k = Math.max(1, Math.min(8, Math.floor(((Ht - 2 * pad) * d) / (bx.h - HEAD)), Math.floor(((W - 2 * pad) * d) / bx.w)));
    const cw = (bx.w * k) / d, ch = (bx.h * k) / d;
    const snap = (v) => Math.round(v * d) / d;
    const sw = I3.sweptBox(6), rb = I3.restBox(6, (FRONT * Math.PI) / 180), u = k / d;
    const visW = (rb.maxX - rb.minX) * u, visH = (rb.maxY - rb.minY) * u, offX = (rb.minX - sw.minX) * u, offY = (rb.minY - sw.minY) * u;
    const g = (Ht - visH) / 2;
    const left = snap(Math.max(pad, W - g - visW) - offX), top = snap(g - offY);
    art.style.top = -hh + "px";
    const key = ["home", k, d].join("|");
    if (!fitCache || fitCache.key !== key) { interruptIntro(); fitCache = { key, r: i3.fit((bx.w * k + 0.5) / d, (bx.h * k + 0.5) / d) }; }
    if (islHost.parentNode !== art) art.appendChild(islHost);
    Object.assign(islHost.style, { left: left + "px", top: top + "px", width: cw + "px", height: ch + "px", paddingLeft: "0px", paddingTop: "0px" });
    $(".body.home")?.classList.toggle("under", left < colR);
    islHost.style.visibility = H.solo ? "hidden" : "";
    i3.set(islandTarget());
  }
  /* no WebGL: the still island at a whole pixel scale, centred in the space right of the column */
  function fitIsland(art) {
    const img = $("#isl");
    if (!img) return;
    if (H.solo) { img.style.display = "none"; return; }
    const nw = 204, nh = 207, d = dpr(), W = app.clientWidth, Hh = app.clientHeight, pad = 8 * suPx();
    const col = $(".col"), colR = col ? col.getBoundingClientRect().right - app.getBoundingClientRect().left : 0;
    const k = Math.max(1, Math.min(Math.floor(((Hh - 2 * pad) * d) / nh), Math.floor(((W - colR - 2 * pad) * d) / nw)));
    const w = (nw * k) / d, h = (nh * k) / d;
    Object.assign(img.style, { display: "", width: w + "px", height: h + "px", left: Math.round((colR + (W - colR - w) / 2) * d) / d + "px", top: Math.round(((Hh - h) / 2) * d) / d + "px" });
  }

  /* What opening Cobblify shows: a normal open shows the whole island, no motion; the first open after an update
   * replays the setup build at 1.4 s, then What's New. Any click or key ends the build at once (the parts left drop
   * quickly). Under reduced motion the island is whole at once. */
  const nextFrame = () => new Promise((r) => win.requestAnimationFrame(() => r()));
  const within = (p, ms) => Promise.race([p, new Promise((r) => win.setTimeout(r, ms))]);
  const wait = (ms) => new Promise((r) => win.setTimeout(r, ms));
  async function playIntro() {
    const tok = ++introTok;
    if (!isl3 || !H || H.solo || view !== "home" || reduced()) return;
    app.style.visibility = "hidden"; /* never show the whole island for a frame before it builds */
    openBuild = { k: 0 };
    isl3.pace(0.7);
    placeIsland();
    await within(nextFrame(), 200);
    app.style.visibility = "";
    for (let k = 1; k <= 6; k++) {
      await wait(k === 1 ? 120 : 100);
      if (tok !== introTok) break;
      openBuild.k = k;
      placeIsland();
    }
    if (tok === introTok) await wait(isl3.buildEnd ? isl3.buildEnd() : 0);
    if (tok === introTok) { openBuild = null; isl3.pace(1); placeIsland(); }
  }
  function interruptIntro() {
    if (!openBuild) return;
    introTok++;
    openBuild = null;
    if (isl3) { isl3.pace(0.3); placeIsland(); win.setTimeout(() => isl3 && isl3.pace(1), 900); }
  }
  ["pointerdown", "wheel", "click"].forEach((ev) => app.addEventListener(ev, interruptIntro, { capture: true, passive: true }));
  doc.addEventListener("keydown", interruptIntro, true);
  win.addEventListener("blur", interruptIntro);
  doc.addEventListener("visibilitychange", interruptIntro);

  /* ---------- interactions ---------- */
  app.addEventListener("click", (e) => {
    if (menuOpen && !e.target.closest(".menu,.caret")) toggleMenu(false);
    const b = e.target.closest("[data-act]");
    if (!b || !app.contains(b) || b.disabled || b.getAttribute("aria-disabled") === "true") return;
    const a = b.dataset.act;
    if (a === "menu") toggleMenu();
    else if (a === "play") actions.play(H.sel);
    else if (a === "cancel") actions.cancel();
    else if (a === "sel") { menuOpen = false; toggleMenu(false); if (b.dataset.id !== H.sel) actions.select(b.dataset.id); }
    else if (a === "add-client") { toggleMenu(false); actions.add(H.add); }
    else if (a === "setup") {
      const k = b.dataset.k;
      const action = k != null ? H.keys[Number(k)]?.action : H.hero.action;
      if (action) actions.setup(action);
    }
    else if (a === "cancel-add") actions.cancelAdd();
    else if (a === "pick") actions.pick(b.dataset.id);
    else if (a === "repair-pref") actions.repairPref();
    else if (a === "update") actions.update(b.dataset.action || H.hero.action);
    else if (a === "settings") (sheetOpen ? closeSheet : openSheet)();
    else if (a === "close") closeSheet();
    else if (a === "history") { modal = null; sheetOpen = false; $("#sheet").classList.remove("open"); view = "history"; render(); $('.hdr [data-act="back"]')?.focus({ preventScroll: true }); }
    else if (a === "back") { view = "home"; render(); $(".ver")?.focus({ preventScroll: true }); }
    else if (a === "jump") {
      histSel = b.dataset.v;
      app.querySelectorAll(".hist-list .row").forEach((r) => r.classList.toggle("sel", r.dataset.v === histSel));
      const el = $("#rel-" + histSel.replace(/\./g, "_"));
      if (el) $("#reading").scrollTo?.({ top: el.offsetTop - 12 * suPx() });
      nudge();
    }
    else if (a === "close-modal") closeModal();
    else if (a === "tog") {
      const on = b.getAttribute("aria-checked") !== "true";
      b.setAttribute("aria-checked", String(on));
      ({ overlay: actions.setOverlay, auto: actions.setAutoUpdate, dev: actions.setDev })[b.dataset.key]?.(on);
    }
    else if (a === "repair-lunar") actions.repairLunar();
    else if (a === "sheet-add") { closeSheet(); if (S?.prismAdd) actions.add(S.prismAdd); }
  });
  doc.addEventListener("keydown", (e) => {
    if (!active) return;
    if (e.key === "Escape") {
      if (menuOpen) { toggleMenu(false); menuKey()?.focus({ preventScroll: true }); }
      else if (modal) closeModal();
      else if (sheetOpen) closeSheet();
      else if (view === "history") { view = "home"; render(); }
    } else if (e.key === "Enter" && !sheetOpen && !modal && view === "home" && !e.target.closest?.("button")) {
      $(".hero:not(.prog):not([aria-disabled])")?.click();
    }
  });
  if (typeof win.ResizeObserver === "function") new win.ResizeObserver(() => { if (active && H) after(); }).observe(app);
  doc.addEventListener("cobblify:units", () => { fitCache = null; if (H) render(); });
  doc.addEventListener("cobblify:theme", () => { if (H) render(); });

  return {
    /** Re-render from a new model. */
    update({ home, sheet, version: v, dev }) {
      H = home;
      S = sheet;
      version = v ?? version;
      devTag = !!dev;
      if (menuOpen && (H.solo || H.launching)) menuOpen = false;
      render();
    },
    /** The home window is hidden while the post-launch dashboard is up (stage 5 replaces that view). */
    setActive(on) {
      active = !!on;
      app.hidden = !active;
      if (isl3) isl3.setActive(active && !!H && !H.solo && view === "home");
      if (active && H) render();
    },
    /** The open behaviour: the build replay then What's New after an update; a still island otherwise. */
    async open(info) {
      if (!info?.updated) return;
      await playIntro();
      openWhatsNew();
    },
    get state() {
      return { menuOpen, sheetOpen, modal, view };
    },
  };
}
