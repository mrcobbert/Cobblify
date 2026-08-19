/** @typedef {'joining' | 'disconnected'} InterstitialMode */

/** @type {Record<InterstitialMode, { title: string, subtitle: string, loading: boolean }>} */
export const CONNECTION_COPY = {
  joining: {
    title: "Joining Hypixel",
    subtitle: "Connecting to network",
    loading: true,
  },
  disconnected: {
    title: "Disconnected from Hypixel",
    subtitle: "Reconnect in Minecraft to resume.",
    loading: false,
  },
};

/**
 * @param {InterstitialMode} mode
 */
export function connectionCopy(mode) {
  return CONNECTION_COPY[mode] ?? CONNECTION_COPY.joining;
}
