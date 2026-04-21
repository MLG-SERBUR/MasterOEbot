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

        Connect4CommandListener listener = new Connect4CommandListener();

        JDA jda = JDABuilder.createDefault(config.token())
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(listener)
                .build()
                .awaitReady();

        listener.registerCommands(jda.updateCommands());
        System.out.println("Connect4 bot is online.");
    }
}
