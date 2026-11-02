#!/usr/bin/env python3
"""Exercise the real Forge/Lunar session adapters without starting Minecraft.

Only platform services are stubbed; the watcher, tally and parsers are production
sources. Requires Python 3 and a JDK (JAVA_HOME, or javac/java on PATH). Each case
runs in a fresh JVM so static session state cannot leak between cases.
"""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

STUBS = {
    'com/bedwarsqol/config/ClientSettings.java': '''package com.bedwarsqol.config;
public class ClientSettings { public boolean sessionStatsEnabled,sessionStatsHoldKey; public int sessionStatsKeyCode; }''',
    'com/bedwarsqol/stats/GameSessionTracker.java': '''package com.bedwarsqol.stats;
public class GameSessionTracker { public static int currentSessionId(){return 1;} }''',
    'com/bedwarsqol/stats/HypixelContext.java': '''package com.bedwarsqol.stats;
import net.minecraft.client.Minecraft;
public class HypixelContext {
 public static boolean hypixel=true, active=true, networkThread;
 private static void client(){if(networkThread)throw new AssertionError("context read on network thread");}
 public static boolean isOnHypixel(){client();return hypixel;}
 public static boolean isInBedwars(){client();return Minecraft.getMinecraft().theWorld!=null;}
 public static boolean isInActiveBedwarsGame(){client();return active && isInBedwars();}
 public static boolean isInBedwarsQueue(){client();return !active && isInBedwars();}
}''',
    'com/bedwarsqol/feature/DiagLog.java': '''package com.bedwarsqol.feature;
public class DiagLog {
 public static final java.util.List<String> lines=new java.util.ArrayList<String>();
 public static void log(String s){lines.add(s);}
}''',
    'com/bedwarsqol/feature/TeamColors.java': '''package com.bedwarsqol.feature;
public class TeamColors {public static char code(String s){return 'c';} }''',
    'com/bedwarsqol/feature/ModChat.java': '''package com.bedwarsqol.feature;
import net.minecraft.util.IChatComponent;
public class ModChat {public static boolean isMarked(IChatComponent c){return false;} }''',
    'net/minecraft/util/IChatComponent.java': '''package net.minecraft.util;
public class IChatComponent {public String text; public IChatComponent(String s){text=s;} public String getUnformattedText(){return text;} }''',
    'net/minecraft/util/EnumChatFormatting.java': '''package net.minecraft.util;
public class EnumChatFormatting {public static String getTextWithoutFormattingCodes(String s){return s.replaceAll("§.","");} }''',
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Player;
import net.minecraft.world.World;
public class Minecraft {
 private static final Minecraft INSTANCE=new Minecraft();
 public World theWorld=new World(); public Player thePlayer=new Player(); public Object currentScreen;
 public NetHandlerPlayClient net=new NetHandlerPlayClient();
 public final java.util.Queue<Runnable> tasks=new java.util.ArrayDeque<Runnable>();
 public static Minecraft getMinecraft(){return INSTANCE;}
 public NetHandlerPlayClient getNetHandler(){return thePlayer==null ? null : net;}
 public Object addScheduledTask(Runnable r){tasks.add(r);return null;}
 public void drain(){while(!tasks.isEmpty())tasks.remove().run();}
}''',
    'net/minecraft/world/World.java': '''package net.minecraft.world;
public class World {public java.util.List<Object> loadedEntityList=new java.util.ArrayList<Object>(); }''',
    'net/minecraft/entity/Player.java': '''package net.minecraft.entity;
public class Player {
 public Inventory inventory=new Inventory(); public String getName(){return "Self";}
 public java.util.UUID getUniqueID(){return new java.util.UUID(0,1);}
 public static class Inventory {public net.minecraft.item.ItemStack[] armorInventory={new net.minecraft.item.ItemStack()};}
}''',
    'net/minecraft/client/network/NetHandlerPlayClient.java': '''package net.minecraft.client.network;
import net.minecraft.network.NetworkManager;
public class NetHandlerPlayClient {
 public NetworkManager manager=new NetworkManager();
 public NetworkManager getNetworkManager(){return manager;}
 public NetworkPlayerInfo getPlayerInfo(java.util.UUID u){return null;}
}''',
    'net/minecraft/client/network/NetworkPlayerInfo.java': '''package net.minecraft.client.network;
public class NetworkPlayerInfo {
 public Profile getGameProfile(){return null;}
 public static class Profile {public String getName(){return "Self";} }
}''',
    'net/minecraft/network/NetworkManager.java': '''package net.minecraft.network;
public class NetworkManager {public boolean open=true;public boolean isChannelOpen(){return open;} }''',
    'net/minecraft/network/Packet.java': '''package net.minecraft.network;
public interface Packet<T> {}''',
    'net/minecraft/network/play/server/S02PacketChat.java': '''package net.minecraft.network.play.server;
import net.minecraft.util.IChatComponent;
public class S02PacketChat implements net.minecraft.network.Packet<Object> {
 private final IChatComponent component;private final byte type;
 public S02PacketChat(String s,int t){component=new IChatComponent(s);type=(byte)t;}
 public IChatComponent getChatComponent(){return component;} public byte getType(){return type;}
 public boolean isChat(){return type==1 || type==2;}
}''',
    'net/minecraft/network/play/server/S45PacketTitle.java': '''package net.minecraft.network.play.server;
import net.minecraft.util.IChatComponent;
public class S45PacketTitle implements net.minecraft.network.Packet<Object> {
 public enum Type {TITLE,SUBTITLE,TIMES,CLEAR,RESET}
 private final Type type;private final IChatComponent component;
 public S45PacketTitle(Type t,String s){type=t;component=s==null?null:new IChatComponent(s);}
 public Type getType(){return type;} public IChatComponent getMessage(){return component;}
}''',
    'net/minecraft/item/ItemStack.java': '''package net.minecraft.item; public class ItemStack {}''',
    'net/minecraft/entity/Entity.java': '''package net.minecraft.entity;
public class Entity {public String getCustomNameTag(){return "";} }''',
    'net/minecraft/entity/item/EntityArmorStand.java': '''package net.minecraft.entity.item;
public class EntityArmorStand extends net.minecraft.entity.Entity {}''',
    'net/weavemc/api/event/SubscribeEvent.java': '''package net.weavemc.api.event;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface SubscribeEvent {}''',
    'net/weavemc/api/event/PacketEvent.java': '''package net.weavemc.api.event;
public class PacketEvent {
 public static class Receive {private final net.minecraft.network.Packet<?> p;
 public Receive(net.minecraft.network.Packet<?> p){this.p=p;} public net.minecraft.network.Packet<?> getPacket(){return p;} }
}''',
    'net/weavemc/api/event/ChatEvent.java': '''package net.weavemc.api.event;
public class ChatEvent {public static class Received {private final net.minecraft.util.IChatComponent c;
 public Received(String s){c=new net.minecraft.util.IChatComponent(s);} public net.minecraft.util.IChatComponent getMessage(){return c;} } }''',
    'net/weavemc/api/event/TickEvent.java': '''package net.weavemc.api.event;
public class TickEvent {public static class Post {}}''',
    'net/minecraftforge/client/event/ClientChatReceivedEvent.java': '''package net.minecraftforge.client.event;
public class ClientChatReceivedEvent {public byte type; public net.minecraft.util.IChatComponent message;
 public ClientChatReceivedEvent(byte t,net.minecraft.util.IChatComponent c){type=t;message=c;} }''',
    'net/minecraftforge/fml/common/eventhandler/EventPriority.java': '''package net.minecraftforge.fml.common.eventhandler;
public enum EventPriority {HIGHEST}''',
    'net/minecraftforge/fml/common/eventhandler/SubscribeEvent.java': '''package net.minecraftforge.fml.common.eventhandler;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface SubscribeEvent {EventPriority priority() default EventPriority.HIGHEST;boolean receiveCanceled() default false;}''',
    'net/minecraftforge/fml/common/gameevent/TickEvent.java': '''package net.minecraftforge.fml.common.gameevent;
public class TickEvent {public enum Phase {START,END} public static class ClientTickEvent {public Phase phase=Phase.END;} }''',
    'org/lwjgl/input/Keyboard.java': '''package org.lwjgl.input;
public class Keyboard {public static final int KEYBOARD_SIZE=256; public static boolean isKeyDown(int c){return false;} }''',
    'org/lwjgl/input/Mouse.java': '''package org.lwjgl.input;
public class Mouse {public static int getButtonCount(){return 3;} public static boolean isButtonDown(int c){return false;} }''',
}

PROBE = r'''import com.bedwarsqol.feature.*;
import com.bedwarsqol.stats.HypixelContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Player;
import net.minecraft.world.World;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.*;
import java.lang.reflect.*;

public class SessionAdapterProbe {
 static final Minecraft MC=Minecraft.getMinecraft();
 static final SessionStatsWatch WATCH=new SessionStatsWatch();
 static final SessionStats CORE=SessionStatsWatch.core();
 static boolean lunar;
 static void dispatch(Object event)throws Exception {
  for(Method m:SessionStatsWatch.class.getMethods()) {
   if(m.getParameterTypes().length!=1 || m.getParameterTypes()[0]!=event.getClass())continue;
   if(m.getAnnotations().length>0)m.invoke(WATCH,event);
  }
 }
 static void packet(Packet<?> p,boolean drain)throws Exception {
  HypixelContext.networkThread=true;
  try {dispatch(new PacketEvent.Receive(p));} finally {HypixelContext.networkThread=false;}
  if(drain)MC.drain();
 }
 static void chat(String s)throws Exception {
  if(lunar)packet(new S02PacketChat(s,0),true);
  else dispatch(new net.minecraftforge.client.event.ClientChatReceivedEvent((byte)0,new IChatComponent(s)));
 }
 static void title(String s)throws Exception {
  if(lunar)packet(new S45PacketTitle(S45PacketTitle.Type.TITLE,s),true);
  else SessionStatsWatch.class.getMethod("onTitle",String.class).invoke(null,s);
 }
 static void ticks()throws Exception {
  for(int i=0;i<10;i++)dispatch(lunar?new TickEvent.Post():new net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent());
 }
 static void completed()throws Exception {chat("Opponent was killed by Self.");title("VICTORY!");tally(1,1,1);}
 static void resetLoggedOnce(String reason)throws Exception {
  ticks();int count=0;
  for(String line:DiagLog.lines)if(line.equals("session: reset reason="+reason))count++;
  if(count!=1)throw new AssertionError("reset must log once: "+reason+" count="+count);
 }
 static void tally(int g,int w,int k) {
  if(CORE.games()!=g || CORE.winstreak()!=w || CORE.kills()!=k)
   throw new AssertionError("expected games="+g+" streak="+w+" kills="+k+"; actual "+CORE.games()+","+CORE.winstreak()+","+CORE.kills());
 }
 public static void main(String[] args)throws Exception {
  lunar=args[0].equals("lunar");String test=args[1];
  if(test.equals("raw-hidden-chat")) {chat("Opponent was killed by Self.");chat("Red - Self");tally(1,1,1);}
  else if(test.equals("raw-hidden-title")) {chat("Opponent was killed by Self.");title("VICTORY!");tally(1,1,1);}
  else if(test.equals("gui-replay")) {
   completed();dispatch(new ChatEvent.Received("Opponent was killed by Self."));
   dispatch(new ChatEvent.Received("[12:34] Red - Self"));
   try {SessionStatsWatch.class.getMethod("onTitle",String.class).invoke(null,"VICTORY!");}catch(NoSuchMethodException expected){}
   tally(1,1,1);
  }
  else if(test.equals("world-gap")) {
   completed();tally(1,1,1);NetHandlerPlayClient net=MC.net;
   MC.theWorld=null;MC.thePlayer=null;MC.net=null;ticks();tally(1,1,1);
   MC.theWorld=new World();MC.thePlayer=new Player();MC.net=net;ticks();tally(1,1,1);
  }
  else if(test.equals("disconnect-stale-host")) {
   completed();MC.net.manager.open=false;MC.theWorld=null;MC.thePlayer=null;MC.net=null;ticks();tally(0,0,0);resetLoggedOnce("closed");
  }
  else if(test.equals("disconnect-world-still-present")) {completed();MC.net.manager.open=false;ticks();tally(0,0,0);resetLoggedOnce("closed");}
  else if(test.equals("offserver")) {completed();HypixelContext.hypixel=false;ticks();tally(0,0,0);resetLoggedOnce("offhost");}
  else if(test.equals("replacement-connection")) {completed();MC.net=new NetHandlerPlayClient();ticks();tally(0,0,0);resetLoggedOnce("replaced");}
  else if(test.equals("stale-queued")) {
   ticks();packet(new S02PacketChat("Opponent was killed by Self.",0),false);
   packet(new S45PacketTitle(S45PacketTitle.Type.TITLE,"VICTORY!"),false);
   MC.net.manager.open=false;MC.net=new NetHandlerPlayClient();ticks();MC.drain();tally(0,0,0);
  }
  else if(test.equals("initial-packet-burst") || test.equals("reconnect-packet-burst")) {
   if(test.equals("reconnect-packet-burst")) {ticks();MC.net.manager.open=false;}
   final NetHandlerPlayClient next=new NetHandlerPlayClient();
   MC.thePlayer=null;MC.theWorld=null;MC.net=null;
   // The earlier JoinGame packet has queued initialization, but the client has not run it yet.
   MC.tasks.add(() -> {MC.theWorld=new World();MC.thePlayer=new Player();MC.net=next;});
   packet(new S02PacketChat("Opponent was killed by Self.",0),false);
   packet(new S45PacketTitle(S45PacketTitle.Type.TITLE,"VICTORY!"),false);
   tally(0,0,0);MC.drain();tally(1,1,1);
  }
  else if(test.equals("packet-thread-and-order")) {
   ticks();packet(new S02PacketChat("Opponent was killed by Self.",0),false);
   packet(new S45PacketTitle(S45PacketTitle.Type.TITLE,"VICTORY!"),false);
   tally(0,0,0);MC.drain();tally(1,1,1);
  }
  else if(test.equals("immutable-packet-text")) {
   ticks();S02PacketChat p=new S02PacketChat("Opponent was killed by Self.",0);packet(p,false);
   p.getChatComponent().text="[12:34] Opponent was killed by Self.";MC.drain();title("VICTORY!");tally(1,1,1);
  }
  else if(test.equals("actionbar")) {
   ticks();
   if(lunar)packet(new S02PacketChat("Opponent was killed by Self.",2),true);
   else dispatch(new net.minecraftforge.client.event.ClientChatReceivedEvent((byte)2,new IChatComponent("Opponent was killed by Self.")));
   tally(0,0,0);
  }
  else if(test.equals("non-title-packets")) {
   ticks();for(S45PacketTitle.Type t:S45PacketTitle.Type.values())if(t!=S45PacketTitle.Type.TITLE)packet(new S45PacketTitle(t,"VICTORY!"),true);
   tally(0,0,0);
  }
  else if(test.equals("chat-and-title")) {completed();chat("Red - Self");chat("1st Killer - Self - 1 Kills");title("VICTORY!");tally(1,1,1);}
  else if(test.equals("nick")) {chat("You are now nicked as Disguise!");chat("Opponent was killed by Disguise.");title("VICTORY!");tally(1,1,1);}
  else throw new AssertionError("unknown test "+test);
  System.out.println("PASS "+args[0]+" "+test);
 }
}'''

CASES = {
    'forge': ['raw-hidden-chat', 'raw-hidden-title', 'world-gap', 'disconnect-stale-host',
              'disconnect-world-still-present', 'offserver', 'replacement-connection',
              'actionbar', 'chat-and-title', 'nick'],
    'lunar': ['raw-hidden-chat', 'raw-hidden-title', 'gui-replay', 'world-gap',
              'disconnect-stale-host', 'disconnect-world-still-present', 'offserver',
              'replacement-connection', 'stale-queued', 'initial-packet-burst', 'reconnect-packet-burst',
              'packet-thread-and-order', 'immutable-packet-text',
              'actionbar', 'non-title-packets', 'chat-and-title', 'nick'],
}


def java_tool(name):
    if os.environ.get('JAVA_HOME'):
        candidate = Path(os.environ['JAVA_HOME']) / 'bin' / name
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise SystemExit('Missing JDK tool: ' + name)
    return found


def main():
    platforms = [sys.argv[1]] if len(sys.argv) > 1 else list(CASES)
    failures = []
    with tempfile.TemporaryDirectory(prefix='session-adapter-tests-') as tmp:
        work = Path(tmp)
        source_paths = []
        for name, body in dict(STUBS, **{'SessionAdapterProbe.java': PROBE}).items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
            source_paths.append(str(path))
        shared = [ROOT / 'common/src/main/java/com/bedwarsqol/feature' / name for name in
                  ['SessionStats.java', 'SessionChatLine.java', 'SessionHoldKey.java', 'GameChatLine.java']]
        for platform in platforms:
            classes = work / platform
            classes.mkdir()
            watcher = ROOT / ('lunar/' if platform == 'lunar' else '') / 'src/main/java/com/bedwarsqol/feature/SessionStatsWatch.java'
            subprocess.run([java_tool('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                            '-Xlint:-options', '-d', str(classes)] + source_paths + list(map(str, shared)) + [str(watcher)], check=True)
            cases = [sys.argv[2]] if len(sys.argv) > 2 else CASES[platform]
            for case in cases:
                result = subprocess.run([java_tool('java'), '-cp', str(classes), 'SessionAdapterProbe', platform, case],
                                        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode:
                    failures.append(platform + ' ' + case)
                    print('FAIL ' + platform + ' ' + case)
                    print(result.stderr.strip())
                else:
                    print(result.stdout.strip())
    print(str(len(failures)) + ' failed')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
