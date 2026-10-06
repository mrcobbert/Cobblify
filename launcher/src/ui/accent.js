/**
 * The game's accent tokens and their base colours, so the launcher wears the accent the player picked in game
 * (0.18 decision 8). Mirrors common/.../gui/render/GuiTheme.java `enum Accent`; accent.test.js checks the two
 * stay identical.
 */
export const ACCENTS = {
  "pale-red": "#FFCAC7", "pale-orange": "#FFCDB0", "pale-yellow": "#ECD8A4", "pale-lime": "#C5E6A9",
  "pale-green": "#B7E8B9", "pale-teal": "#ADE7D8", "pale-aqua": "#B0E4EB", "pale-sky": "#B3E1FA",
  "pale-blue": "#C0DCFF", "pale-purple": "#DAD2FF", "pale-magenta": "#F6C6FD", "pale-pink": "#FDC6E5",
  red: "#E5484D", orange: "#F0770F", yellow: "#F5C63A", lime: "#9BE34A", green: "#3FB950", teal: "#02CAAB",
  aqua: "#3FD0E0", sky: "#37B2E8", blue: "#4A90E2", purple: "#A78BFA", magenta: "#CF66E0", pink: "#F27EC4",
  "deep-red": "#C33038", "deep-orange": "#A75001", "deep-yellow": "#816503", "deep-lime": "#477401",
  "deep-green": "#017922", "deep-teal": "#047664", "deep-aqua": "#03737E", "deep-sky": "#007099",
  "deep-blue": "#2769B4", "deep-purple": "#7357BA", "deep-magenta": "#A03EAF", "deep-pink": "#A94283",
  white: "#E6E6E6", silver: "#B8B8B8", grey: "#8A8A8A",
};

/** A brand-new player has no game config yet: the launcher opens in Pale Blue with the Minecraft font. */
export const NEW_PLAYER_THEME = Object.freeze({ accent: ACCENTS["pale-blue"], font: "minecraft" });

/**
 * The launcher theme for a `game_theme` reply ({ accent?, font? } tokens). With no config at all, the new-player
 * theme. With one, exactly what the game shows: an unknown accent token is Orange and anything but "minecraft"
 * is Modern (GuiTheme.fromToken / normalizeFont).
 */
export function themeFor(reply) {
  const accent = reply?.accent;
  const font = reply?.font;
  if (!accent && !font) return { ...NEW_PLAYER_THEME };
  return {
    accent: (accent && ACCENTS[accent]) || ACCENTS.orange,
    font: font === "minecraft" ? "minecraft" : "modern",
  };
}
