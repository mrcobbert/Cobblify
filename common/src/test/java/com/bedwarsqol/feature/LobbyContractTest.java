package com.bedwarsqol.feature;

import com.bedwarsqol.stats.PlayerCard;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * A8 (Java half): the serialised DTO has exactly the keys of the shared fixture
 * {@code lobby-contract/valid/full.json}, which the launcher's JS and Rust validators also consume.
 * Also pins that a roster copy loses no field.
 */
public class LobbyContractTest {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    static JsonObject fixture(String path) {
        InputStream in = LobbyContractTest.class.getResourceAsStream("/lobby-contract/" + path);
        assertNotNull("fixture missing: " + path, in);
        return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static TreeSet<String> keys(JsonObject o) {
        TreeSet<String> out = new TreeSet<String>();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) out.add(e.getKey());
        return out;
    }

    static LobbyExport.Player fullPlayer() {
        LobbyExport.Player p = new LobbyExport.Player("Sweat");
        p.state = "OK";
        p.nicked = true;
        p.realName = "RealSweat";
        p.rank = "[MVP+]";
        p.rankCodes = "§b[MVP§c+§b]";
        p.mode = "4s";
        p.fkdr = 12.5;
        p.wlr = 4.2;
        p.finalKills = 12345;
        p.kd = 3.1;
        p.fkdrTier = 3;
        p.cheater = true;
        p.badge = new PlayerCard.Chip("BC", "§6", "Blatant Cheater", false);
        p.chips.add(p.badge);
        p.chips.add(new PlayerCard.Chip("SL", "§a", "Safelisted", true));
        p.seraphThreat = 4;
        p.presence = GameRoster.ELIMINATED;
        return p;
    }

    @Test
    public void serialisedShapeMatchesSharedFixture() {
        JsonObject fx = fixture("valid/full.json");

        LobbyExport.Lobby lobby = new LobbyExport.Lobby();
        LobbyExport.Team team = new LobbyExport.Team("Red");
        team.players.add(fullPlayer());
        lobby.teams.add(team);
        lobby.players.add(fullPlayer());
        lobby.yourParty.add(fullPlayer());
        JsonObject root = GSON.toJsonTree(lobby).getAsJsonObject();
        root.addProperty("seq", 1); // injected by the writer, not a DTO field

        assertEquals(keys(fx), keys(root));
        assertEquals(2, root.get("v").getAsInt());
        assertEquals(2, fx.get("v").getAsInt());

        JsonObject fxPlayer = fx.getAsJsonArray("players").get(0).getAsJsonObject();
        JsonObject player = root.getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals(keys(fxPlayer), keys(player));

        JsonObject fxChip = fxPlayer.getAsJsonArray("chips").get(0).getAsJsonObject();
        JsonObject chip = player.getAsJsonArray("chips").get(0).getAsJsonObject();
        assertEquals(keys(fxChip), keys(chip));
        assertEquals(keys(fxChip), keys(player.getAsJsonObject("badge")));

        JsonObject fxTeam = fx.getAsJsonArray("teams").get(0).getAsJsonObject();
        JsonObject teamJson = root.getAsJsonArray("teams").get(0).getAsJsonObject();
        assertEquals(keys(fxTeam), keys(teamJson));
    }

    @Test
    public void rosterCopyLosesNoField() {
        GameRoster r = new GameRoster();
        r.observe(7, 1_000L, java.util.Arrays.asList(new LobbyExport.TabRow("Sweat", "Red")), null);
        LobbyExport.Player original = fullPlayer();
        r.withRetainedStats("Sweat", original);
        LobbyExport.Player copy = r.withRetainedStats("Sweat", new LobbyExport.Player("Sweat"));
        // presence is not stat memory: the copy carries the retained row's value.
        assertEquals(GSON.toJson(original), GSON.toJson(copy));
    }
}
