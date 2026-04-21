package com.masteroebot.connect4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
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
        StartupProbe probe = new StartupProbe();
        JDABuilder builder = JDABuilder.createDefault(token)
                .addEventListeners(listener, probe);

        if (enableMessageContent) {
            builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
        }

        JDA jda = builder.build();
        StartupOutcome outcome = probe.await(30, TimeUnit.SECONDS);

        if (outcome == StartupOutcome.DISALLOWED_INTENTS && enableMessageContent) {
            jda.shutdownNow();
            System.err.println("MESSAGE_CONTENT denied by Discord. Restarting without prefix fallback.");
            return null;
        }

        if (outcome == StartupOutcome.SHUTDOWN) {
            throw new IllegalStateException("JDA shutdown during startup: " + probe.closeCode());
        }
        if (outcome == StartupOutcome.TIMEOUT) {
            throw new IllegalStateException("Timed out waiting for Discord startup.");
        }

        return new BootResult(jda, listener);
    }

    private record BootResult(JDA jda, Connect4CommandListener listener) {
    }

    private enum StartupOutcome {
        READY,
        DISALLOWED_INTENTS,
        SHUTDOWN,
        TIMEOUT
    }

    private static final class StartupProbe extends ListenerAdapter {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile StartupOutcome outcome;
        private volatile CloseCode closeCode;

        @Override
        public void onReady(ReadyEvent event) {
            outcome = StartupOutcome.READY;
            done.countDown();
        }

        @Override
        public void onShutdown(ShutdownEvent event) {
            closeCode = event.getCloseCode();
            outcome = closeCode == CloseCode.DISALLOWED_INTENTS ? StartupOutcome.DISALLOWED_INTENTS : StartupOutcome.SHUTDOWN;
            done.countDown();
        }

        StartupOutcome await(long timeout, TimeUnit unit) throws InterruptedException {
            if (!done.await(timeout, unit)) {
                outcome = StartupOutcome.TIMEOUT;
            }
            return outcome;
        }

        CloseCode closeCode() {
            return closeCode;
        }
    }
}
