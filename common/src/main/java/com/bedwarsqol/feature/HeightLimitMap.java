package com.bedwarsqol.feature;

/**
 * One Bedwars map's build envelope as shipped in {@link HeightLimitTable}. {@link #maxY} is the
 * highest Y a block can be placed at (Lunar's "Height Limit" number: Lighthouse = 110); the server
 * denies placement at {@code maxY + 1} and above. {@link #minY} is the lowest placeable Y and
 * {@link #radius} the horizontal build radius from the map centre (both 0 = unknown). Pure data.
 */
public final class HeightLimitMap {

    public final String name;
    /** 8 for the Solo/Doubles pool, 4 for 3v3v3v3/4v4v4v4, 0 when unknown. */
    public final int teams;
    public final int minY;
    public final int maxY;
    public final int radius;

    public HeightLimitMap(String name, int teams, int minY, int maxY, int radius) {
        this.name = name;
        this.teams = teams;
        this.minY = minY;
        this.maxY = maxY;
        this.radius = radius;
    }

    @Override
    public String toString() {
        return name + "{teams=" + teams + ", minY=" + minY + ", maxY=" + maxY + ", radius=" + radius + "}";
    }
}
