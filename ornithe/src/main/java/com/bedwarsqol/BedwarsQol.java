package com.bedwarsqol;

import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.command.BedwarsQolCommand;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.SettingsManager;
import com.bedwarsqol.event.EventPriority;
import com.bedwarsqol.feature.AutoGg;
import com.bedwarsqol.feature.CameraRules;
import com.bedwarsqol.feature.ToggleSprint;
import com.bedwarsqol.feature.BlockOverlayRenderer;
import com.bedwarsqol.feature.ChatNameTags;
import com.bedwarsqol.feature.ChatNotifications;
import com.bedwarsqol.feature.ClutchPractice;
import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.feature.GameRosterChat;
import com.bedwarsqol.feature.HeightLimitWatch;
import com.bedwarsqol.feature.IncSender;
import com.bedwarsqol.feature.LobbyChatWatch;
import com.bedwarsqol.feature.NametagStats;
import com.bedwarsqol.feature.NickUtils;
import com.bedwarsqol.feature.OutgoingChat;
import com.bedwarsqol.feature.PartyJoinAlert;
import com.bedwarsqol.feature.PauseKeyHandler;
import com.bedwarsqol.feature.QueueAlert;
import com.bedwarsqol.feature.SessionStatsWatch;
import com.bedwarsqol.feature.SettingsKeyHandler;
import com.bedwarsqol.feature.SweatReport;
import com.bedwarsqol.feature.TeamUpgradesWatch;
import com.bedwarsqol.feature.TntFuseDisplay;
import com.bedwarsqol.feature.UrchinAlert;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.ornithe.ClientCommands;
import com.bedwarsqol.ornithe.ModEvents;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.GameSessionTracker;
import net.minecraft.client.settings.KeyBinding;
import net.ornithemc.osl.entrypoints.api.client.ClientModInitializer;
import net.ornithemc.osl.keybinds.api.KeybindEvents;
import net.ornithemc.osl.keybinds.api.KeybindRegistry;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

import java.io.InputStream;
import java.util.Properties;

/**
 * Ornithe entry point. Replaces the Forge {@code @Mod} lifecycle with OSL events:
 * <ul>
 *   <li>{@code KeybindEvents.REGISTER_KEYBINDS} (inside {@code GameSettings}' constructor, before
 *       options.txt loads) loads the config and registers the vanilla key bindings, so they persist
 *       in options.txt as they did on Forge;</li>
 *   <li>{@code MinecraftClientEvents.READY} (end of {@code Minecraft.startGame}) registers every
 *       feature on {@link ModEvents} and the client command, then logs the ready line;</li>
 *   <li>{@code MinecraftClientEvents.TICK_END} (end of {@code runTick}, Forge's client tick END)
 *       feeds {@link ModEvents#CLIENT_TICK}.</li>
 * </ul>
 * Listener order and priorities match the Forge registration order.
 */
public class BedwarsQol implements ClientModInitializer {

    public static final String MODID = "bedwarsqol";
    public static final String NAME = "Cobblify";
    public static final String VERSION = readVersion();

    private static final Logger LOGGER = LogManager.getLogger("Cobblify");
    /** The CONTRACT ready line the launcher waits for in latest.log. */
    static final String READY_LINE = "Cobblify ready (ornithe)";

    public static ClientSettings config;
    public static KeyBinding settingsKeyBinding;
    /** Rebindable key (default unbound) that opens the vanilla pause menu — see {@link com.bedwarsqol.feature.PauseKeyHandler}. */
    public static KeyBinding pauseKeyBinding;
    /** Rebindable key (default unbound) that sends /pc INC — see {@link com.bedwarsqol.feature.IncSender}. */
    public static KeyBinding incKeyBinding;
    /** Hold to zoom (default C; options.txt {@code key_Zoom}) — see {@link com.bedwarsqol.feature.CameraOptions}. */
    public static KeyBinding zoomKeyBinding;
    /** Press to toggle sprint (default G; options.txt {@code key_Toggle Sprint}) — see {@link com.bedwarsqol.feature.ToggleSprint}. */
    public static KeyBinding toggleSprintKeyBinding;

    @Override
    public void initClient() {
        KeybindEvents.REGISTER_KEYBINDS.register(BedwarsQol::registerKeybinds);
        MinecraftClientEvents.READY.register(mc -> onReady());
        MinecraftClientEvents.TICK_END.register(ModEvents.CLIENT_TICK::post);
    }

    private static void loadConfig() {
        if (config != null) return;
        config = SettingsManager.load();
        DiagLog.init("Cobblify v" + VERSION + " (ornithe)");
    }

    private static void registerKeybinds() {
        loadConfig();
        settingsKeyBinding = KeybindRegistry.register(
                new KeyBinding("Open Cobblify Settings", config.settingsKeyCode, "Cobblify"));
        pauseKeyBinding = KeybindRegistry.register(new KeyBinding("Open Game Menu", Keyboard.KEY_NONE, "Cobblify"));
        incKeyBinding = KeybindRegistry.register(new KeyBinding("Send /pc INC", Keyboard.KEY_NONE, "Cobblify"));
        zoomKeyBinding = KeybindRegistry.register(
                new KeyBinding(CameraRules.ZOOM_KEY_NAME, CameraRules.DEFAULT_ZOOM_KEY, "Cobblify"));
        toggleSprintKeyBinding = KeybindRegistry.register(new KeyBinding(
                com.bedwarsqol.feature.SprintRules.TOGGLE_SPRINT_KEY_NAME,
                com.bedwarsqol.feature.SprintRules.DEFAULT_TOGGLE_SPRINT_KEY, "Cobblify"));
    }

    private static void onReady() {
        loadConfig();
        ClientCommands.register(new BedwarsQolCommand());

        // Same order as the Forge build's EVENT_BUS registrations; only GameRosterChat and
        // SessionStatsWatch listen to chat at HIGHEST with receiveCanceled, as on Forge (plus the
        // Ornithe-only TeamUpgradesWatch, so no other listener can hide a line from it).
        SettingsKeyHandler settingsKey = new SettingsKeyHandler(settingsKeyBinding);
        ModEvents.CLIENT_TICK.register(mc -> settingsKey.onClientTick());
        PauseKeyHandler pauseKey = new PauseKeyHandler(pauseKeyBinding);
        ModEvents.CLIENT_TICK.register(mc -> pauseKey.onClientTick());
        ToggleSprint toggleSprint = new ToggleSprint(toggleSprintKeyBinding);
        ModEvents.CLIENT_TICK.register(mc -> toggleSprint.onClientTick());
        IncSender incSender = new IncSender(incKeyBinding);
        ModEvents.CLIENT_TICK.register(mc -> incSender.onKeyInput());
        ChatNotifications chatNotifications = new ChatNotifications();
        ModEvents.CHAT.register(chatNotifications::onChat);
        ModEvents.CLIENT_TICK.register(mc -> chatNotifications.onClientTick());
        BedwarsHudRenderer hud = new BedwarsHudRenderer();
        ModEvents.HUD_TEXT.register(partialTicks -> hud.onRenderText());
        ModEvents.HUD_POST.register(partialTicks -> hud.onRenderPost());
        ModEvents.MOUSE.register(hud::onMouse);
        NametagStats nametagStats = new NametagStats();
        ModEvents.NAMETAG_POST.register(nametagStats::onRenderNameTag);
        BedwarsModeDetector modeDetector = new BedwarsModeDetector();
        ModEvents.CLIENT_TICK.register(mc -> modeDetector.onClientTick());
        ModEvents.WORLD_LOAD.register(world -> modeDetector.onWorldLoad());
        OutgoingChat outgoing = OutgoingChat.get();
        ModEvents.CLIENT_TICK.register(mc -> outgoing.onClientTick());
        ModEvents.WORLD_LOAD.register(world -> outgoing.onWorldLoad());
        ModEvents.CHAT.register(outgoing::onChat);
        SweatReport sweatReport = new SweatReport();
        ModEvents.WORLD_LOAD.register(world -> sweatReport.onWorldLoad());
        ModEvents.CLIENT_TICK.register(mc -> sweatReport.onClientTick());
        AutoGg autoGg = new AutoGg();
        ModEvents.WORLD_LOAD.register(world -> autoGg.onWorldLoad());
        ModEvents.CHAT.register(autoGg::onChat);
        ModEvents.CLIENT_TICK.register(mc -> autoGg.onClientTick());
        PartyJoinAlert partyJoinAlert = new PartyJoinAlert();
        ModEvents.WORLD_LOAD.register(world -> partyJoinAlert.onWorldLoad());
        ModEvents.CHAT.register(partyJoinAlert::onChat);
        ModEvents.CLIENT_TICK.register(mc -> partyJoinAlert.onClientTick());
        GameRosterChat rosterChat = new GameRosterChat();
        ModEvents.CHAT.register(EventPriority.HIGHEST, true, rosterChat::onChat);
        NickUtils nickUtils = new NickUtils();
        ModEvents.WORLD_LOAD.register(world -> nickUtils.onWorldLoad());
        ModEvents.CLIENT_TICK.register(mc -> nickUtils.onClientTick());
        ChatNameTags chatNameTags = new ChatNameTags();
        ModEvents.WORLD_LOAD.register(world -> chatNameTags.onWorldLoad());
        ModEvents.CHAT.register(chatNameTags::onChat);
        ModEvents.CLIENT_TICK.register(mc -> chatNameTags.onClientTick());
        GeneratorTracker generators = new GeneratorTracker();
        ModEvents.CLIENT_TICK.register(mc -> generators.onClientTick());
        ModEvents.WORLD_LOAD.register(world -> generators.onWorldLoad());
        ModEvents.CHAT.register(generators::onChat);
        BlockOverlayRenderer blockOverlay = new BlockOverlayRenderer();
        ModEvents.BLOCK_HIGHLIGHT.register(blockOverlay::onDrawBlockHighlight);
        TntFuseDisplay tntFuse = new TntFuseDisplay();
        ModEvents.HUD_TEXT.register(partialTicks -> tntFuse.onRenderText());
        GameSessionTracker sessionTracker = new GameSessionTracker();
        ModEvents.CLIENT_TICK.register(mc -> sessionTracker.onClientTick());
        UrchinAlert urchinAlert = new UrchinAlert();
        ModEvents.CLIENT_TICK.register(mc -> urchinAlert.onClientTick());
        QueueAlert queueAlert = new QueueAlert();
        ModEvents.CHAT.register(queueAlert::onChat);
        LobbyChatWatch lobbyChatWatch = new LobbyChatWatch();
        ModEvents.CHAT.register(lobbyChatWatch::onChat);
        SessionStatsWatch sessionStats = new SessionStatsWatch();
        ModEvents.CHAT.register(EventPriority.HIGHEST, true, sessionStats::onChat);
        ModEvents.CLIENT_TICK.register(mc -> sessionStats.onClientTick());
        HeightLimitWatch heightLimit = new HeightLimitWatch();
        ModEvents.SERVER_CONNECT.register(heightLimit::onServerConnect);
        ModEvents.CHAT.register(heightLimit::onChat);
        ModEvents.CLIENT_TICK.register(mc -> heightLimit.onClientTick());
        TeamUpgradesWatch teamUpgrades = new TeamUpgradesWatch();
        ModEvents.SERVER_CONNECT.register(teamUpgrades::onServerConnect);
        ModEvents.WORLD_LOAD.register(world -> teamUpgrades.onWorldLoad());
        ModEvents.CHAT.register(EventPriority.HIGHEST, true, teamUpgrades::onChat);
        ModEvents.CLIENT_TICK.register(mc -> teamUpgrades.onClientTick());
        // Clutch Practice's four server-side hooks are called by its own mixins (ExplosionMixin,
        // EntityPlayerMixin, ItemInWorldManagerMixin); they act only once it is enabled here.
        ClutchPractice.enable();

        LOGGER.info(READY_LINE);
    }

    /** The mod version, filled into com/bedwarsqol/version.properties by the build. */
    private static String readVersion() {
        try (InputStream in = BedwarsQol.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty("version");
                if (version != null && !version.isEmpty() && !version.contains("$")) return version;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "unknown";
    }
}
