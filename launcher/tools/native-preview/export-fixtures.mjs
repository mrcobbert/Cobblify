// Build-time fixture data only. These are the production browser's sample rosters.
import { PREVIEW_LOBBY } from "../../src/board-fixtures.js";
process.stdout.write(JSON.stringify(Object.fromEntries(
  ["lobby", "queueFours", "gameSolo", "gameDoubles", "unsupported"].map((key) => [key, PREVIEW_LOBBY[key]]),
)));
