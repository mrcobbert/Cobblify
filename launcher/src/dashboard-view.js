import { isOutdatedSnapshot } from "./lobby-validator.js";

const NOTE = "Not Available";
const OUTDATED = "Update Cobblify to use the overlay";

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
  const note = `<div class="empty-note">${NOTE}</div>`;
  return { title: "", sub: "", blocks: [{ rows: [], foot: note }], bare: true };
}

/** A well-formed snapshot from an older mod jar: the launcher cannot draw its rows. */
export function viewOutdated() {
  const note = `<div class="empty-note">${OUTDATED}</div>`;
  return { title: "", sub: "", blocks: [{ rows: [], foot: note }], bare: true };
}

/** Choose the outdated note, the unsupported empty state, or the caller's normal roster view. */
export function dashboardView(d, fallbackViewOf) {
  if (isOutdatedSnapshot(d)) return viewOutdated();
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
export const OUTDATED_NOTE = OUTDATED;
