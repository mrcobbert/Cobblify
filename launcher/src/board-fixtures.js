// The board's sample snapshots: the `npm run dev` preview (?ctx=…) draws them, and board-render.js measures the game
// ones as the board's layout reference (the worst case, Doubles: 16 players in 8 teams), so the layout never depends
// on the live roster. They are the fixtures the 0.18 board was designed and approved against.

import { CONTRACT_VERSION } from "./lobby-validator.js";

// Shaped exactly like a v2 lobby.json row: the mod has already decided the
// tier, the cheater flag, the badge and the chips, so the sample carries them.
const RANK_CODES = {
  "[MVP++]": "§6[MVP§c++§6]",
  "[MVP+]": "§b[MVP§c+§b]",
  "[MVP]": "§b[MVP]",
  "[VIP+]": "§a[VIP§6+§a]",
  "[VIP]": "§a[VIP]",
};
const chip = (code, color, label, positive = false) => ({ code, color, label, positive });
const tagged = (chips, cheater) => ({ chips, badge: chips[0] ?? null, cheater });
const pp = (o) =>
  Object.assign(
    {
      state: "OK",
      nicked: false,
      realName: null,
      rank: "",
      rankCodes: RANK_CODES[o.rank] ?? "",
      mode: "Overall",
      fkdr: 0,
      wlr: 0,
      finalKills: 0,
      kd: 0,
      fkdrTier: 0,
      cheater: false,
      badge: null,
      chips: [],
      seraphThreat: -1,
    },
    o,
  );

const P = {
  you: pp({ name: "you_", rank: "[MVP+]", fkdrTier: 2, fkdr: 5.9, wlr: 3.8, finalKills: 15200, kd: 3.2 }),
  duo: pp({ name: "duo_diff", rank: "[VIP+]", fkdrTier: 2, fkdr: 7.2, wlr: 4.9, finalKills: 19000, kd: 3.9 }),
  third: pp({ name: "third_wheeler", rank: "[MVP]", fkdrTier: 1, fkdr: 3.1, wlr: 2.2, finalKills: 6400, kd: 2.4 }),
  clutch: pp({ name: "clutch_or_kick", rank: "[VIP]", fkdrTier: 0, fkdr: 1.8, wlr: 1.1, finalKills: 2100, kd: 1.4 }),
  shadow: pp({ name: "xXShadowSlayerXx", rank: "[MVP++]", fkdrTier: 3, fkdr: 22.7, wlr: 14.0, finalKills: 71200, kd: 8.9, seraphThreat: 4, ...tagged([chip("BC", "§6", "Blatant Cheater"), chip("BL", "§4", "Blacklisted (verified)")], true) }),
  wallhax: pp({ name: "wallhax_", rank: "[VIP]", fkdrTier: 3, fkdr: 19.1, wlr: 11.4, finalKills: 60300, kd: 7.7, ...tagged([chip("CC", "§6", "Closet Cheater")], true) }),
  tenko: pp({ name: "Tenko", rank: "[MVP++]", fkdrTier: 3, fkdr: 14.2, wlr: 9.1, finalKills: 48210, kd: 6.7, seraphThreat: 2 }),
  zenith: pp({ name: "ok_zenith", rank: "[MVP+]", fkdrTier: 2, fkdr: 8.4, wlr: 5.2, finalKills: 22140, kd: 4.1 }),
  prot: pp({ name: "Prot_IV_Diamond", rank: "[MVP+]", fkdrTier: 2, fkdr: 6.8, wlr: 4.4, finalKills: 17700, kd: 3.6 }),
  frost: pp({ name: "GodBridgeGod", nicked: true, realName: "Frostbyte_", rank: "[MVP]", fkdrTier: 1, fkdr: 4.6, wlr: 3.1, finalKills: 9800, kd: 2.8 }),
  moss: pp({ name: "mossling", rank: "[MVP]", fkdrTier: 1, fkdr: 3.3, wlr: 2.0, finalKills: 5600, kd: 2.2 }),
  ogre: pp({ name: "TheObsidianOgre", rank: "[MVP]", fkdrTier: 1, fkdr: 2.9, wlr: 1.8, finalKills: 4900, kd: 2.0 }),
  aqua: pp({ name: "aqua_gg", rank: "[VIP+]", fkdrTier: 1, fkdr: 2.1, ...tagged([chip("SL", "§a", "Safelisted", true)], false), wlr: 1.4, finalKills: 3120, kd: 1.6 }),
  virus: pp({ name: "notavirus_exe", rank: "[VIP+]", fkdrTier: 0, fkdr: 1.6, wlr: 1.2, finalKills: 2440, kd: 1.3, ...tagged([chip("S", "§4", "Sniper")], false) }),
  bread: pp({ name: "Bread_Enjoyer", rank: "[VIP]", fkdrTier: 0, fkdr: 1.05, wlr: 0.9, finalKills: 880, kd: 1.1 }),
  cool: pp({ name: "coolkid2013", rank: "", fkdrTier: 0, fkdr: 0.42, wlr: 0.5, finalKills: 120, kd: 0.7 }),
  nick: pp({ name: "iToxicWaffle", rank: "", state: "NICKED", nicked: true }),
  grandpa: pp({ name: "Grandpa_Joe", rank: "", state: "NEVER_PLAYED" }),
  lag: pp({ name: "lagswitch99", rank: "[VIP]", state: "LOADING" }),
  sniped: pp({ name: "sniped_u", rank: "", state: "LOADING" }),
};

/** Preview only: the same player, out of the game. */
const out = (p, presence) => ({ ...p, presence });

const PREVIEW_PARTY1 = [P.you];
const PREVIEW_PARTY = [P.you, P.duo];
const PREVIEW_PARTY3 = [P.you, P.duo, P.third];
const PREVIEW_PARTY4 = [P.you, P.duo, P.third, P.clutch];

function previewSnap(context, extra) {
  return Object.assign(
    {
      v: CONTRACT_VERSION,
      jvmPid: 4242,
      jvmStartTimeMs: 1_700_000_000_000,
      inHypixel: true,
      self: "you_",
      mode: null,
      partyCount: null,
      yourParty: [],
      teams: [],
      players: [],
    },
    extra,
    { context },
  );
}

const team = (name, players) => ({ name, players });

export const PREVIEW_LOBBY = {
  lobby: previewSnap("LOBBY", {
    seq: 1001,
    yourParty: PREVIEW_PARTY,
    players: [P.shadow, P.tenko, P.zenith, P.prot, P.frost, P.moss, P.aqua, P.bread, P.cool, P.nick, P.grandpa, P.lag],
  }),
  unsupported: previewSnap("LOBBY", { seq: 1002, dashboardEligible: false }),
  queueSolo: previewSnap("QUEUE", {
    seq: 1010,
    mode: "Solos",
    partyCount: 8,
    yourParty: PREVIEW_PARTY1,
    players: [P.wallhax, P.tenko, P.sniped],
  }),
  queueDoubles: previewSnap("QUEUE", {
    seq: 1011,
    mode: "Doubles",
    partyCount: 5,
    yourParty: PREVIEW_PARTY,
    players: [P.wallhax, P.tenko, P.zenith, P.frost, P.sniped],
  }),
  queueThrees: previewSnap("QUEUE", {
    seq: 1012,
    mode: "3v3v3v3",
    partyCount: 4,
    yourParty: PREVIEW_PARTY3,
    players: [P.shadow, P.wallhax, P.tenko, P.zenith, P.frost, P.moss, P.nick, P.lag],
  }),
  queueFours: previewSnap("QUEUE", {
    seq: 1013,
    mode: "4v4v4v4",
    partyCount: 6,
    yourParty: PREVIEW_PARTY4,
    players: [P.shadow, P.wallhax, P.tenko, P.zenith, P.prot, P.frost, P.moss, P.ogre, P.aqua, P.virus, P.bread, P.cool, P.nick, P.grandpa, P.lag, P.sniped],
  }),
  queue4v4: previewSnap("QUEUE", {
    seq: 1014,
    mode: "4v4",
    partyCount: 2,
    yourParty: PREVIEW_PARTY4,
    players: [P.shadow, P.wallhax, P.tenko, P.zenith],
  }),
  gameSolo: previewSnap("GAME", {
    seq: 1020,
    mode: "Solos",
    yourParty: PREVIEW_PARTY1,
    teams: [
      team("Red", [P.shadow]),
      team("Blue", [P.you]),
      team("Green", [P.tenko]),
      team("Yellow", [P.zenith]),
      team("Aqua", [P.moss]),
      team("White", [P.bread]),
      team("Pink", [P.nick]),
      team("Gray", [P.grandpa]),
    ],
  }),
  gameDoubles: previewSnap("GAME", {
    seq: 1021,
    mode: "Doubles",
    yourParty: PREVIEW_PARTY,
    teams: [
      team("Red", [P.shadow, P.wallhax]),
      team("Blue", [P.you, P.duo]),
      team("Green", [P.tenko, P.zenith]),
      team("Yellow", [P.prot, P.frost]),
      team("Aqua", [P.moss, P.aqua]),
      team("White", [P.bread, P.cool]),
      team("Pink", [P.virus, P.nick]),
      team("Gray", [P.grandpa, P.lag]),
    ],
  }),
  gameThrees: previewSnap("GAME", {
    seq: 1022,
    mode: "3v3v3v3",
    yourParty: PREVIEW_PARTY,
    teams: [
      team("Red", [P.shadow, P.tenko, P.zenith]),
      team("Blue", [P.you, P.duo, P.bread]),
      team("Green", [P.moss, P.cool, P.grandpa]),
      team("Yellow", [P.aqua, P.nick, P.prot]),
    ],
  }),
  gameFours: previewSnap("GAME", {
    seq: 1023,
    mode: "4v4v4v4",
    yourParty: PREVIEW_PARTY4,
    teams: [
      team("Red", [P.shadow, P.wallhax, P.tenko, out(P.zenith, "ELIMINATED")]),
      team("Blue", [P.you, P.duo, P.third, P.clutch]),
      team("Green", [P.frost, P.moss, out(P.cool, "DISCONNECTED"), out(P.grandpa, "MISSING")]),
      team("Yellow", [
        out(P.virus, "ELIMINATED"),
        out(P.bread, "ELIMINATED"),
        out(P.nick, "ELIMINATED"),
        out(P.prot, "ELIMINATED"),
      ]),
    ],
  }),
  game4v4: previewSnap("GAME", {
    seq: 1024,
    mode: "4v4",
    yourParty: PREVIEW_PARTY4,
    teams: [
      team("Red", [P.shadow, P.wallhax, P.tenko, P.zenith]),
      team("Blue", [P.you, P.duo, P.third, P.clutch]),
    ],
  }),
};
// a well-formed file from an older mod jar: the board asks for an update
PREVIEW_LOBBY.outdated = { ...PREVIEW_LOBBY.lobby, seq: 1003, v: 1 };
PREVIEW_LOBBY.queue = PREVIEW_LOBBY.queueDoubles;
PREVIEW_LOBBY.queue16 = PREVIEW_LOBBY.queueFours;
PREVIEW_LOBBY.game = PREVIEW_LOBBY.gameThrees;
PREVIEW_LOBBY.game16 = PREVIEW_LOBBY.gameFours;
