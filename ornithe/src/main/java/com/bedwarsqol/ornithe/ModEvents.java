package com.bedwarsqol.ornithe;

import com.bedwarsqol.event.EventDispatcher;
import com.bedwarsqol.feature.SessionChatLine;
import com.bedwarsqol.rewind.watch.PlaybackGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

/**
 * The Ornithe build's replacement for {@code MinecraftForge.EVENT_BUS}: one dispatcher per event the
 * features listen to. OSL feeds {@link #CLIENT_TICK}; Cobblify's own mixins feed the rest, at the
 * vanilla positions Forge posts its events from.
 *
 * <p>Every post goes through {@link #post(EventDispatcher, Object)}, which posts nothing while a Rewind recording
 * plays ({@link PlaybackGate#muted()}): Cobblify's features receive no event then (PLAN "Isolation", layer 3).
 */
public final class ModEvents {

    /** End of {@code Minecraft.runTick} (Forge {@code ClientTickEvent}, phase END). */
    public static final EventDispatcher<Minecraft> CLIENT_TICK = new EventDispatcher<>();
    /** Server chat packets, client thread (Forge {@code ClientChatReceivedEvent}). */
    public static final EventDispatcher<ChatReceivedEvent> CHAT = new EventDispatcher<>();
    /** A new client world (Forge {@code WorldEvent.Load} from {@code WorldClient}). */
    public static final EventDispatcher<WorldClient> WORLD_LOAD = new EventDispatcher<>();
    /**
     * HUD text layer, at vanilla's debug-overlay position (Forge {@code RenderGameOverlayEvent.Text}).
     * Not posted in frames where the tab list shows ({@code com.bedwarsqol.hud.TabHudGate}).
     */
    public static final EventDispatcher<Float> HUD_TEXT = new EventDispatcher<>();
    /**
     * End of the in-game HUD frame (Forge {@code RenderGameOverlayEvent.Post}, type ALL). Unlike
     * {@link #HUD_TEXT}, it is posted while the tab list shows: listeners that draw HUD elements must
     * return early when {@code com.bedwarsqol.hud.TabHudGate.hidesHud()} is true.
     */
    public static final EventDispatcher<Float> HUD_POST = new EventDispatcher<>();
    /** In-game mouse loop (Forge {@code MouseEvent}). */
    public static final EventDispatcher<MouseButtonEvent> MOUSE = new EventDispatcher<>();
    /** After a living entity's name tag pass (Forge {@code RenderLivingEvent.Specials.Post}). */
    public static final EventDispatcher<NametagRenderEvent> NAMETAG_POST = new EventDispatcher<>();
    /** Before vanilla's block outline (Forge {@code DrawBlockHighlightEvent}). */
    public static final EventDispatcher<BlockHighlightEvent> BLOCK_HIGHLIGHT = new EventDispatcher<>();
    /** Login finished, netty thread (FML {@code ClientConnectedToServerEvent}). */
    public static final EventDispatcher<ServerConnectEvent> SERVER_CONNECT = new EventDispatcher<>();
    /**
     * One of the player's own Bed Wars game lines, as {@code SessionStatsWatch} classified it (client thread):
     * every kind it hands Session Stats, plus LOSS when the player's own team is eliminated. Observe-only.
     */
    public static final EventDispatcher<SessionChatLine.Kind> GAME_LINE = new EventDispatcher<>();

    private ModEvents() {
    }

    /**
     * The only way to post one of these events: skipped while a Rewind recording plays, otherwise
     * {@code dispatcher.post(event)} unchanged. A skipped post is "not canceled", so vanilla goes on as without
     * Cobblify (recorded chat shows unprocessed). Any thread.
     *
     * @return whether the event ends up canceled; false when skipped
     */
    public static <E> boolean post(EventDispatcher<E> dispatcher, E event) {
        if (PlaybackGate.muted()) return false;
        return dispatcher.post(event);
    }
}
