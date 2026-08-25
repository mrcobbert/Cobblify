/**
 * Shared launcher poll cadences.
 *
 * `LOBBY_POLL_MS` is the most latency-critical constant in the app: every
 * real-world event the launcher reacts to (joining Hypixel, disconnecting,
 * quitting the game) is observed at this quantum, so it sets the floor of
 * every UI-reaction budget. It lived as a private literal in `main.js` with
 * no test holding it, which is exactly how a latency regression ships
 * unnoticed - it is pinned here instead.
 */
export const LOBBY_POLL_MS = 250;
