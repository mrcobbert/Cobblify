package com.bedwarsqol.feature;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shipped build-height table for Hypixel Bedwars maps, keyed by {@link #normalize normalized} map
 * name (map names are unique across the Solo/Doubles and 3s/4s pools, so the name alone identifies
 * the map). Values are the highest placeable Y — the number Lunar's Height Limit HUD shows — plus
 * the lowest placeable Y and the horizontal build radius where known (0 = unknown).
 *
 * <p>Provenance (merged 2026-09-13, see .ai handoff for the rule set): the community
 * HeightLimitMod dataset (pinkulu / Polyfrost DataStorageV2, 159 maps, values stored as the first
 * <i>denied</i> Y so shifted by −1 here) cross-checked against the Bed Wars Map List dataset
 * (jaayminecraft/bedwars, 134 maps in rotation as of 2026-09-07, values stored as the highest
 * placeable Y). Where both agree after the shift that value is used; where only one knows the map
 * its value is used; the three real conflicts (Fort Doon, Temple, Treenan) follow the newer
 * dataset. Cauldron and Gingerbread have no limit in any source and are learned in-game.
 *
 * <p>The table is a prior, not ground truth: {@link HeightLimitCore} corrects it from what the
 * server actually accepts or denies while you play, and persists those corrections.
 */
public final class HeightLimitTable {

    private static final Map<String, HeightLimitMap> BY_KEY;

    static {
        HeightLimitMap[] all = {
            m("Acropolis", 8, 62, 100, 150),
            m("Aetius", 8, 68, 94, 122),
            m("Airshow", 8, 50, 100, 104),
            m("Alaric", 4, 68, 97, 111),
            m("Amazon", 8, 53, 93, 129),
            m("Ambush", 8, 67, 101, 144),
            m("Antenna", 4, 63, 89, 105),
            m("Apollo", 8, 58, 98, 120),
            m("Aqil", 8, 65, 90, 107),
            m("Aquarium", 4, 65, 110, 106),
            m("Arcade", 8, 62, 90, 105),
            m("Archway", 4, 58, 86, 100),
            m("Arid", 8, 54, 83, 129),
            m("Artemis", 4, 67, 96, 102),
            m("Ashfire", 8, 66, 120, 125),
            m("Ashore", 4, 59, 94, 112),
            m("Atlas Prime", 8, 72, 96, 0),
            m("Babylon", 8, 55, 107, 136),
            m("Beeeee", 4, 75, 101, 131),
            m("Bio-Hazard", 8, 62, 95, 139),
            m("Blitzen", 8, 60, 110, 106),
            m("Blossom", 8, 73, 96, 107),
            m("Boardwalk", 4, 67, 92, 95),
            m("Boletum", 4, 60, 85, 92),
            m("Build Site", 4, 63, 96, 104),
            m("Bunnywars", 4, 61, 99, 139),
            m("Burrow", 4, 52, 100, 99),
            m("Carapace", 4, 60, 94, 74),
            m("Cascade", 8, 59, 87, 165),
            m("Casita", 8, 61, 93, 119),
            m("Catalyst", 4, 64, 101, 109),
            m("Chained", 4, 55, 90, 99),
            m("Chalk Cliffs", 4, 69, 107, 94),
            m("Cliffside", 8, 72, 100, 138),
            m("Coastal", 4, 57, 89, 80),
            m("Comet", 4, 75, 115, 0),
            m("Crimson", 4, 0, 106, 0),
            m("Crogorm", 8, 79, 123, 111),
            m("Crypt", 8, 45, 95, 119),
            m("Daolong", 4, 66, 90, 97),
            m("Darkened", 8, 59, 81, 119),
            m("Deadwood", 8, 54, 83, 115),
            m("Deposit", 4, 56, 81, 89),
            m("Dockyard", 8, 62, 97, 121),
            m("Dragon Light", 8, 55, 96, 124),
            m("Dragonstar", 8, 65, 100, 99),
            m("Dreamgrove", 4, 85, 115, 81),
            m("Duye", 8, 67, 95, 129),
            m("Easter Basket", 8, 71, 93, 109),
            m("Easter Garden", 8, 71, 98, 130),
            m("Eastwood", 4, 52, 100, 82),
            m("Echo Ruins", 8, 68, 99, 0),
            m("Egg Hunt", 8, 52, 100, 111),
            m("Egg Run", 4, 63, 98, 105),
            m("Enchanted", 4, 64, 100, 112),
            m("Extinction", 4, 64, 95, 90),
            m("Fang Outpost", 4, 67, 99, 109),
            m("Fireplace", 8, 53, 95, 136),
            m("Fort Doon", 4, 68, 90, 113),
            m("Frogiton", 4, 61, 90, 122),
            m("Frosted", 8, 65, 90, 97),
            m("Fruitbrawl", 8, 63, 100, 116),
            m("Gateway", 8, 88, 128, 118),
            m("Gelato", 8, 58, 80, 103),
            m("Ghoulish", 8, 64, 86, 151),
            m("Glacier", 8, 70, 105, 109),
            m("Graveship", 4, 84, 123, 118),
            m("Grotto", 4, 64, 100, 113),
            m("Hanging Gardens", 8, 55, 107, 137),
            m("Harvest", 8, 60, 83, 128),
            m("Harvesting", 4, 67, 95, 112),
            m("Haven", 4, 39, 72, 121),
            m("Hell Temple", 4, 69, 114, 110),
            m("Highland Peaks", 8, 61, 88, 132),
            m("Hollow", 8, 60, 88, 97),
            m("Holmgang", 4, 61, 97, 102),
            m("Horizon", 4, 65, 100, 106),
            m("Impere", 8, 67, 104, 188),
            m("Infinite", 4, 67, 88, 88),
            m("Invasion", 4, 75, 115, 98),
            m("Ironclad", 8, 55, 87, 130),
            m("Jurassic", 4, 64, 94, 99),
            m("Katsu", 4, 67, 96, 103),
            m("Keep", 8, 34, 61, 128),
            m("Kubo", 4, 60, 91, 129),
            m("Lasagne", 4, 61, 90, 120),
            m("Lectus", 4, 55, 90, 82),
            m("Lighthouse", 8, 60, 110, 99),
            m("Lightstone", 8, 53, 95, 142),
            m("Loft", 4, 55, 82, 97),
            m("Lost Temple", 4, 68, 91, 110),
            m("Lotice", 8, 0, 90, 0),
            m("Lotus", 8, 62, 89, 112),
            m("Lucky Rush", 8, 58, 84, 131),
            m("Lunarhouse", 8, 60, 110, 125),
            m("Manor Royale", 4, 66, 94, 0),
            m("Meadow", 8, 55, 80, 162),
            m("Meso", 8, 64, 95, 118),
            m("Mirage", 8, 44, 86, 115),
            m("Montipora", 8, 64, 98, 133),
            m("Mortuus", 4, 55, 90, 83),
            m("Mystery", 4, 62, 92, 109),
            m("Nebuc", 8, 73, 105, 106),
            m("Nostalgia", 4, 69, 96, 110),
            m("Nutcracker", 8, 58, 98, 121),
            m("Obelisk", 4, 60, 114, 108),
            m("Ominosity", 8, 79, 123, 113),
            m("Orbit", 8, 57, 96, 149),
            m("Orchestra", 8, 67, 106, 127),
            m("Orchid", 4, 65, 86, 93),
            m("Orientwood", 4, 50, 100, 0),
            m("Paladin", 4, 63, 98, 98),
            m("Pantheon", 8, 61, 98, 0),
            m("Paradox", 4, 59, 84, 100),
            m("Pavilion", 8, 68, 97, 139),
            m("Pernicious", 8, 55, 90, 109),
            m("Pharaoh", 4, 65, 95, 111),
            m("Planet 98", 4, 47, 105, 106),
            m("Playground", 8, 53, 100, 117),
            m("Polygon", 8, 67, 93, 115),
            m("Pool Party", 4, 66, 86, 118),
            m("Pumpkin Bay", 4, 55, 85, 101),
            m("Raze", 4, 61, 88, 98),
            m("Relic", 4, 61, 90, 90),
            m("Rigged", 4, 76, 95, 103),
            m("Rise", 4, 69, 96, 95),
            m("Rooftop", 8, 62, 91, 119),
            m("Rooted", 8, 67, 95, 114),
            m("Sanctum", 8, 60, 91, 148),
            m("Sandcastle", 4, 58, 102, 114),
            m("Santa's Rush", 4, 65, 92, 91),
            m("Scareshow", 8, 50, 100, 105),
            m("Scorched Sands", 8, 55, 92, 131),
            m("Screamway", 8, 55, 90, 115),
            m("Seraph", 4, 67, 94, 106),
            m("Serenity", 8, 65, 93, 124),
            m("Shark Attack", 4, 67, 94, 130),
            m("Shipment", 4, 67, 101, 114),
            m("Shipwreck", 4, 19, 59, 0),
            m("Siege", 8, 80, 108, 121),
            m("Silver Birch", 8, 73, 114, 135),
            m("Sky Festival", 4, 64, 94, 95),
            m("Sky Rise", 8, 55, 90, 122),
            m("Slumber", 8, 66, 93, 103),
            m("Snails", 8, 65, 91, 106),
            m("Snowkeep", 4, 54, 95, 97),
            m("Snowy Square", 8, 69, 95, 128),
            m("Solace", 8, 64, 100, 101),
            m("Speedway", 8, 55, 90, 114),
            m("Springtide", 4, 58, 86, 90),
            m("Station", 8, 64, 114, 0),
            m("Steampumpkin", 8, 64, 99, 132),
            m("Steampunk", 8, 64, 99, 131),
            m("Stilted", 4, 57, 80, 97),
            m("Stonekeep", 4, 54, 95, 96),
            m("Sunflower", 8, 67, 95, 99),
            m("Swashbuckle", 4, 55, 85, 100),
            m("Sweet Wonderland", 8, 67, 94, 140),
            m("Symphonic", 8, 67, 106, 128),
            m("Temple", 4, 43, 105, 100),
            m("Tengshe", 4, 64, 100, 114),
            m("Terminal", 4, 58, 87, 93),
            m("Terraced", 8, 0, 90, 0),
            m("Tigris", 4, 68, 101, 115),
            m("Toro", 8, 65, 92, 117),
            m("Treenan", 4, 47, 120, 100),
            m("Trick or Yeet", 8, 65, 94, 126),
            m("Turtle Cove", 4, 69, 98, 100),
            m("Tuzi", 8, 61, 91, 126),
            m("Unchained", 4, 55, 90, 96),
            m("Unturned", 4, 54, 92, 115),
            m("Urban Plaza", 8, 56, 83, 108),
            m("Usagi", 4, 64, 96, 96),
            m("Vigilante", 8, 63, 87, 106),
            m("Waterfall", 8, 58, 100, 99),
            m("Whiskers", 4, 66, 87, 112),
            m("Yandi", 4, 0, 81, 0),
            m("Yue", 8, 73, 101, 109),
            m("Zarzul", 8, 88, 114, 130),
            m("Zen Plaza", 4, 57, 83, 132),
        };
        Map<String, HeightLimitMap> m = new HashMap<>(all.length * 2);
        for (HeightLimitMap e : all) m.put(normalize(e.name), e);
        BY_KEY = Collections.unmodifiableMap(m);
    }

    private HeightLimitTable() {
    }

    private static HeightLimitMap m(String name, int teams, int minY, int maxY, int radius) {
        return new HeightLimitMap(name, teams, minY, maxY, radius);
    }

    /** The shipped entry for a map name as Hypixel reports it (any case/punctuation), or {@code null}. */
    public static HeightLimitMap lookup(String mapName) {
        String key = normalize(mapName);
        return key.isEmpty() ? null : BY_KEY.get(key);
    }

    /** Every shipped entry, keyed by normalized name. */
    public static Map<String, HeightLimitMap> all() {
        return BY_KEY;
    }

    /**
     * Lower-case alphanumerics only: {@code "Bio-Hazard"} → {@code "biohazard"}, {@code "Santa's Rush"} →
     * {@code "santasrush"}. Tolerates the punctuation/spacing differences between the sidebar, the
     * location packet and the datasets. {@code null} → empty.
     */
    public static String normalize(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
