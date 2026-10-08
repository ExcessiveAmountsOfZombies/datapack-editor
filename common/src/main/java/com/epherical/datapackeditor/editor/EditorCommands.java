package com.epherical.datapackeditor.editor;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class EditorCommands {
    private static final Logger log = LoggerFactory.getLogger(EditorCommands.class);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dpackEditor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(context -> start(context.getSource())))
                .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource()))));
    }

    private static int start(CommandSourceStack source) {
        try {
            EditorWebServer.StartResult result = EditorWebServer.start(source.getServer());
            Component link = Component.literal(result.url())
                    .withStyle(style -> style
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, result.url())));
            Component message = Component.literal(result.started()
                            ? "datapack-editor started: "
                            : "datapack-editor is already running: ")
                    .append(link);
            source.sendSuccess(() -> message, false);
            return 1;
        } catch (RuntimeException | LinkageError exception) {
            source.sendFailure(Component.literal("Could not start the datapack-editor: " + exception.getMessage()));
            log.error("What Went Wrong:", exception);
            return 0;
        }
    }

    private static int stop(CommandSourceStack source) {
        try {
            boolean stopped = EditorWebServer.stop();
            source.sendSuccess(
                    () -> Component.literal(stopped
                            ? "datapack-editor stopped."
                            : "datapack-editor is not running."),
                    false
            );
            return stopped ? 1 : 0;
        } catch (RuntimeException | LinkageError exception) {
            source.sendFailure(Component.literal("Could not stop the datapack-editor: " + exception.getMessage()));
            return 0;
        }
    }

    private static int status(CommandSourceStack source) {
        if (!EditorWebServer.isRunning()) {
            source.sendSuccess(() -> Component.literal("datapack-editor is stopped."), false);
            return 0;
        }

        String url = EditorWebServer.editorUrl();
        Component link = Component.literal(url)
                .withStyle(style -> style
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
        source.sendSuccess(() -> Component.literal("datapack-editor is running: ").append(link), false);
        return 1;
    }
}
