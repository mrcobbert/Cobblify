import { dashboardParty } from "./roster-identity.js";

const NOTE = "Not available in this mode.";

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** Absent flag (old mod) is eligible. Only an explicit false hides the roster. */
export function isDashboardEligible(d) {
  return d?.dashboardEligible !== false;
}

export function liveDashboardAction(d, { firstLiveSeen = false, wasDisconnected = false } = {}) {
  return {
    enterDashboard: !firstLiveSeen,
    reconnect: Boolean(firstLiveSeen && wasDisconnected),
    eligible: isDashboardEligible(d),
  };
}

export function viewUnsupported(d) {
  const party = dashboardParty(d);
  const note = `<div class="empty-note">${NOTE}</div>`;
  const blocks = [{ rows: [], foot: note }];
  if (party.length) {
    blocks.push({
      head: `<div class="sect"><h3>Your Party</h3><span class="n">${party.length}</span></div>`,
      rows: party.map(
        (p) =>
          `<div class="prow"><div class="pname"><div class="identity"><span class="pn">${esc(p.name)}</span></div></div></div>`,
      ),
    });
  }
  return { title: "Bed Wars", sub: "", blocks };
}

/** Choose the unsupported empty state or the caller's normal roster view. */
export function dashboardView(d, fallbackViewOf) {
  return isDashboardEligible(d) ? fallbackViewOf(d) : viewUnsupported(d);
}

/**
 * Production live-snapshot UI step: first ineligible snapshot still enters, later eligible
 * snapshots restore the roster view. main.js must call this rather than open-coding it.
 */
export function nextLiveDashboard(d, { firstLiveSeen = false, wasDisconnected = false, eligibleViewOf }) {
  const action = liveDashboardAction(d, { firstLiveSeen, wasDisconnected });
  return {
    ...action,
    view: dashboardView(d, eligibleViewOf),
    nextFirstLiveSeen: firstLiveSeen || action.enterDashboard,
  };
}

export const UNSUPPORTED_NOTE = NOTE;
