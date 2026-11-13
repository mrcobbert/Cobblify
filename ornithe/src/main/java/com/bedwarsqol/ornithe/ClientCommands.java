package com.bedwarsqol.ornithe;

import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side commands for the Ornithe build, which has no Forge {@code ClientCommandHandler} and no
 * OSL commands module. A vanilla {@link CommandHandler} holds the commands, so execution, usage and
 * error messages are vanilla's (as they were through Forge's handler). Two mixins feed it:
 * {@code GuiScreenCommandMixin} (chat submit) and {@code GuiChatCompletionMixin} (tab completion).
 *
 * <p>Unlike Forge's handler, only input starting with {@code /} is treated as a command, so a plain
 * chat line that happens to equal a command name is still sent as chat.
 */
public final class ClientCommands {

    private static final CommandHandler HANDLER = new CommandHandler();

    private ClientCommands() {
    }

    public static void register(ICommand command) {
        HANDLER.registerCommand(command);
    }

    /** The registered command named by {@code message} ({@code /name args...}), or null. */
    static ICommand find(String message) {
        if (message == null) return null;
        String trimmed = message.trim();
        if (!trimmed.startsWith("/")) return null;
        String name = trimmed.substring(1).split(" ")[0];
        return HANDLER.getCommands().get(name);
    }

    /**
     * Runs {@code message} if it names one of our commands.
     *
     * @return true when the message was ours (run or answered with an error) and must not reach the
     *         server
     */
    public static boolean execute(ICommandSender sender, String message) {
        if (sender == null || find(message) == null) return false;
        HANDLER.executeCommand(sender, message);
        return true;
    }

    /**
     * Completions for {@code leftOfCursor} (which starts with {@code /}): command names while the
     * first word is typed, else the matching command's own completions. Empty when nothing matches.
     */
    public static List<String> complete(ICommandSender sender, String leftOfCursor, BlockPos pos) {
        if (sender == null || leftOfCursor == null || !leftOfCursor.startsWith("/")) return Collections.emptyList();
        String input = leftOfCursor.substring(1);
        if (input.indexOf(' ') >= 0 && find(leftOfCursor) == null) return Collections.emptyList();
        List<String> options = HANDLER.getTabCompletionOptions(sender, input, pos);
        if (options == null || options.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(options);
        if (input.indexOf(' ') < 0) {
            // Command names complete with their slash, as Forge's handler offered them.
            for (int i = 0; i < out.size(); i++) out.set(i, "/" + out.get(i));
        }
        return out;
    }
}
