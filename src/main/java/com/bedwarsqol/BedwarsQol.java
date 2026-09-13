package com.bedwarsqol;

import com.bedwarsqol.bedwars.GeneratorTracker;
import com.bedwarsqol.command.BedwarsQolCommand;
import com.bedwarsqol.config.ClientSettings;
import com.bedwarsqol.config.SettingsManager;
import com.bedwarsqol.feature.AutoGg;
import com.bedwarsqol.feature.BlockOverlayRenderer;
import com.bedwarsqol.feature.ChatNameTags;
import com.bedwarsqol.feature.ChatNotifications;
import com.bedwarsqol.feature.DiagLog;
import com.bedwarsqol.feature.IncSender;
import com.bedwarsqol.feature.OutgoingChat;
import com.bedwarsqol.feature.TntFuseDisplay;
import com.bedwarsqol.feature.NametagStats;
import com.bedwarsqol.feature.NickUtils;
import com.bedwarsqol.feature.GameRosterChat;
import com.bedwarsqol.feature.PartyJoinAlert;
import com.bedwarsqol.feature.PauseKeyHandler;
import com.bedwarsqol.feature.PlayersKeyHandler;
import com.bedwarsqol.feature.QueueAlert;
import com.bedwarsqol.feature.SettingsKeyHandler;
import com.bedwarsqol.feature.SweatReport;
import com.bedwarsqol.feature.SeraphAlert;
import com.bedwarsqol.feature.UrchinAlert;
import com.bedwarsqol.hud.BedwarsHudRenderer;
import com.bedwarsqol.stats.BedwarsModeDetector;
import com.bedwarsqol.stats.GameSessionTracker;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(modid = BedwarsQol.MODID, name = BedwarsQol.NAME, version = BedwarsQol.VERSION)
public class BedwarsQol {

    public static final String MODID = "@ID@";
    public static final String NAME = "@NAME@";
    public static final String VERSION = "@VER@";

    @Mod.Instance(MODID)
    public static BedwarsQol INSTANCE;

    public static ClientSettings config;
    public static KeyBinding settingsKeyBinding;
    /** Rebindable key (default unbound) that opens the vanilla pause menu — see {@link com.bedwarsqol.feature.PauseKeyHandler}. */
    public static KeyBinding pauseKeyBinding;
    /** Rebindable key (default unbound) that sends /pc INC — see {@link com.bedwarsqol.feature.IncSender}. */
    public static KeyBinding incKeyBinding;
    /** Rebindable key (default unbound) that opens the Players tab — see {@link com.bedwarsqol.feature.PlayersKeyHandler}. */
    public static KeyBinding playersKeyBinding;

    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        config = SettingsManager.load();
        DiagLog.init("Cobblify v" + VERSION + " (forge)");
        settingsKeyBinding = new KeyBinding("Open Cobblify Settings", config.settingsKeyCode, "Cobblify");
        ClientRegistry.registerKeyBinding(settingsKeyBinding);
        pauseKeyBinding = new KeyBinding("Open Game Menu", Keyboard.KEY_NONE, "Cobblify");
        ClientRegistry.registerKeyBinding(pauseKeyBinding);
        incKeyBinding = new KeyBinding("Send /pc INC", Keyboard.KEY_NONE, "Cobblify");
        ClientRegistry.registerKeyBinding(incKeyBinding);
        playersKeyBinding = new KeyBinding("Open Cobblify Players", Keyboard.KEY_NONE, "Cobblify");
        ClientRegistry.registerKeyBinding(playersKeyBinding);
        ClientCommandHandler.instance.registerCommand(new BedwarsQolCommand());
        MinecraftForge.EVENT_BUS.register(new SettingsKeyHandler(settingsKeyBinding));
        MinecraftForge.EVENT_BUS.register(new PauseKeyHandler(pauseKeyBinding));
        MinecraftForge.EVENT_BUS.register(new IncSender(incKeyBinding));
        MinecraftForge.EVENT_BUS.register(new PlayersKeyHandler(playersKeyBinding));
        MinecraftForge.EVENT_BUS.register(new ChatNotifications());
        MinecraftForge.EVENT_BUS.register(new BedwarsHudRenderer());
        MinecraftForge.EVENT_BUS.register(new NametagStats());
        MinecraftForge.EVENT_BUS.register(new BedwarsModeDetector());
        MinecraftForge.EVENT_BUS.register(OutgoingChat.get());
        MinecraftForge.EVENT_BUS.register(new SweatReport());
        MinecraftForge.EVENT_BUS.register(new AutoGg());
        MinecraftForge.EVENT_BUS.register(new PartyJoinAlert());
        MinecraftForge.EVENT_BUS.register(new GameRosterChat());
        MinecraftForge.EVENT_BUS.register(new NickUtils());
        MinecraftForge.EVENT_BUS.register(new ChatNameTags());
        MinecraftForge.EVENT_BUS.register(new GeneratorTracker());
        MinecraftForge.EVENT_BUS.register(new BlockOverlayRenderer());
        MinecraftForge.EVENT_BUS.register(new TntFuseDisplay());
        MinecraftForge.EVENT_BUS.register(new GameSessionTracker());
        MinecraftForge.EVENT_BUS.register(new UrchinAlert());
        MinecraftForge.EVENT_BUS.register(new SeraphAlert());
        MinecraftForge.EVENT_BUS.register(new QueueAlert());
        MinecraftForge.EVENT_BUS.register(new com.bedwarsqol.feature.LobbyChatWatch());
    }
}
