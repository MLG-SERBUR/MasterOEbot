package com.masteroebot.connect4;

import java.io.IOException;
import java.nio.file.Path;

import javax.security.auth.login.LoginException;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class BotMain {
    public static void main(String[] args) throws LoginException, InterruptedException, IOException {
        Path configPath = Path.of("config.yaml");
        BotConfig config = BotConfig.load(configPath);

        BootResult boot = startBot(config.token(), true);
        if (boot == null) {
            boot = startBot(config.token(), false);
        }

        Connect4CommandListener listener = boot.listener();
        JDA jda = boot.jda();
        listener.registerCommands(jda.updateCommands());
        System.out.println("Connect4 bot is online.");
    }

    private static BootResult startBot(String token, boolean enableMessageContent) throws LoginException, InterruptedException {
        Connect4CommandListener listener = new Connect4CommandListener(enableMessageContent);
        JDABuilder builder = JDABuilder.createDefault(token)
                .addEventListeners(listener);

        if (enableMessageContent) {
            builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
        }

        try {
            JDA jda = builder.build().awaitReady();
            return new BootResult(jda, listener);
        } catch (IllegalStateException ex) {
            if (enableMessageContent && isDisallowedIntents(ex)) {
                System.err.println("MESSAGE_CONTENT denied by Discord. Restarting without prefix fallback.");
                return null;
            }
            throw ex;
        }
    }

    private static boolean isDisallowedIntents(IllegalStateException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("DISALLOWED_INTENTS");
    }

    private record BootResult(JDA jda, Connect4CommandListener listener) {
    }
}
