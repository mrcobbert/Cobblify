package com.bedwarsqol.rewind;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.scoreboard.IScoreObjectiveCriteria;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * HEADER source 0 (PLAN "Carry-over header"): the client state as of a world boundary, copied on the client thread
 * at {@code WORLD_LOAD} into plain values, which {@link CarryOverEncoder} turns into protocol-47 packets.
 *
 * <p>{@code WORLD_LOAD} fires inside the boundary packet's own client-thread handler, after every earlier enqueued
 * packet was applied and before any later one, and before the new world replaces {@code theWorld}. So the world,
 * player, scoreboard and tab list read here are the pre-boundary state: an S07 segment's synthetic S01 names the
 * dimension it leaves. The tab header (S47) is not read: vanilla applies it on the Netty thread, so the capture
 * handler freezes it in stream order instead (HEADER source 1). Nothing here ever builds an S47.
 *
 * <p>Client thread only, once per world switch (allocates).
 */
public final class CarryOverSnapshot {

    private CarryOverSnapshot() {
    }

    /** The source-0 packets in feed order: S01, S38, S3B, S3E, S3C, S3D, S39, S09. Never an S47. */
    public static List<byte[]> packets(CarryOverEncoder.Snapshot s) {
        return CarryOverEncoder.encode(s);
    }

    /**
     * The pre-boundary state the client holds now. {@code net} is the connection's play handler (the tab list and
     * the server's max players); null before it exists. Missing parts (no world or player before a connection's
     * first boundary) keep the defaults.
     */
    public static CarryOverEncoder.Snapshot capture(Minecraft mc, NetHandlerPlayClient net) {
        CarryOverEncoder.Snapshot s = new CarryOverEncoder.Snapshot();
        WorldClient world = mc == null ? null : mc.theWorld;
        EntityPlayerSP player = mc == null ? null : mc.thePlayer;
        WorldInfo info = world == null ? null : world.getWorldInfo();
        joinGame(s,
                player == null ? 0 : player.getEntityId(),
                mc == null || mc.playerController == null ? null : mc.playerController.getCurrentGameType(),
                info != null && info.isHardcoreModeEnabled(),
                world != null ? world.provider.getDimensionId() : player != null ? player.dimension : 0,
                world == null ? null : world.getDifficulty(),
                net == null ? 0 : net.currentServerMaxPlayers,
                info == null ? null : info.getTerrainType(),
                player != null && player.hasReducedDebug());
        tabList(s, net == null ? null : net.getPlayerInfoMap());
        scoreboard(s, world == null ? null : world.getScoreboard());
        abilities(s, player == null ? null : player.capabilities, player == null ? 0 : player.inventory.currentItem);
        return s;
    }

    /** The synthetic S01's fields; null enums take vanilla's defaults (survival, peaceful, {@code default}). */
    static void joinGame(CarryOverEncoder.Snapshot s, int entityId, WorldSettings.GameType gameType, boolean hardcore,
                         int dimension, EnumDifficulty difficulty, int maxPlayers, WorldType type, boolean reducedDebug) {
        int mode = gameType == null ? WorldSettings.GameType.SURVIVAL.getID() : gameType.getID();
        if (hardcore) {
            mode |= 8;
        }
        s.entityId = entityId;
        s.gameType = mode;
        s.dimension = dimension;
        s.difficulty = difficulty == null ? EnumDifficulty.PEACEFUL.getDifficultyId() : difficulty.getDifficultyId();
        s.maxPlayers = maxPlayers;
        s.levelType = (type == null ? WorldType.DEFAULT : type).getWorldTypeName();
        s.reducedDebug = reducedDebug;
    }

    /** Every tab entry, for one S38 ADD_PLAYER. */
    static void tabList(CarryOverEncoder.Snapshot s, Collection<NetworkPlayerInfo> infos) {
        if (infos == null) {
            return;
        }
        for (NetworkPlayerInfo info : infos) {
            CarryOverEncoder.TabEntry e = tabEntry(info);
            if (e != null) {
                s.tab.add(e);
            }
        }
    }

    static CarryOverEncoder.TabEntry tabEntry(NetworkPlayerInfo info) {
        GameProfile profile = info == null ? null : info.getGameProfile();
        if (profile == null || profile.getId() == null) {
            return null;
        }
        List<CarryOverEncoder.Property> props = new ArrayList<CarryOverEncoder.Property>();
        for (Property p : profile.getProperties().values()) {
            props.add(new CarryOverEncoder.Property(p.getName(), p.getValue(), p.hasSignature() ? p.getSignature() : null));
        }
        WorldSettings.GameType mode = info.getGameType();
        IChatComponent display = info.getDisplayName();
        return new CarryOverEncoder.TabEntry(profile.getId().getMostSignificantBits(),
                profile.getId().getLeastSignificantBits(), profile.getName(), props,
                mode == null ? WorldSettings.GameType.NOT_SET.getID() : mode.getID(), info.getResponseTime(),
                display == null ? null : IChatComponent.Serializer.componentToJson(display));
    }

    /** Objectives, teams with members, scores, and the occupied display slots 0-18. */
    static void scoreboard(CarryOverEncoder.Snapshot s, Scoreboard board) {
        if (board == null) {
            return;
        }
        for (ScoreObjective o : board.getScoreObjectives()) {
            IScoreObjectiveCriteria.EnumRenderType render = o.getRenderType();
            s.objectives.add(new CarryOverEncoder.Objective(o.getName(), o.getDisplayName(),
                    render == IScoreObjectiveCriteria.EnumRenderType.HEARTS ? "hearts" : "integer"));
        }
        for (ScorePlayerTeam t : board.getTeams()) {
            int flags = (t.getAllowFriendlyFire() ? 1 : 0) | (t.getSeeFriendlyInvisiblesEnabled() ? 2 : 0);
            Team.EnumVisible tags = t.getNameTagVisibility();
            EnumChatFormatting colour = t.getChatFormat();
            s.teams.add(new CarryOverEncoder.Team(t.getRegisteredName(), t.getTeamName(), t.getColorPrefix(),
                    t.getColorSuffix(), flags, (tags == null ? Team.EnumVisible.ALWAYS : tags).internalName,
                    colour == null ? -1 : colour.getColorIndex(), new ArrayList<String>(t.getMembershipCollection())));
        }
        for (Score sc : board.getScores()) {
            ScoreObjective o = sc.getObjective();
            if (o != null) {
                s.scores.add(new CarryOverEncoder.Score(sc.getPlayerName(), o.getName(), sc.getScorePoints()));
            }
        }
        for (int slot = 0; slot < CarryOverEncoder.DISPLAY_SLOTS; slot++) {
            ScoreObjective o = board.getObjectiveInDisplaySlot(slot);
            s.displaySlots[slot] = o == null ? null : o.getName();
        }
    }

    /** S39 from the player's capabilities (vanilla defaults when null) and S09's hotbar slot. */
    static void abilities(CarryOverEncoder.Snapshot s, PlayerCapabilities caps, int heldSlot) {
        PlayerCapabilities c = caps == null ? new PlayerCapabilities() : caps;
        s.invulnerable = c.disableDamage;
        s.flying = c.isFlying;
        s.allowFlying = c.allowFlying;
        s.creative = c.isCreativeMode;
        s.flySpeed = c.getFlySpeed();
        s.walkSpeed = c.getWalkSpeed();
        s.heldSlot = heldSlot;
    }
}
