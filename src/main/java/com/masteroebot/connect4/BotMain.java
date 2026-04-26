package com.masteroebot.connect4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;

import com.masteroebot.markov.MarkovConfig;
import com.masteroebot.markov.MarkovListener;
import com.masteroebot.markov.MarkovManager;

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

        MarkovManager markovManager = new MarkovManager();
        MarkovConfig markovConfig = new MarkovConfig();
        markovConfig.load();

        BootResult boot = startBot(config.token(), true, markovManager, markovConfig);
        boolean markovAvailable = (boot != null && boot.markovListener() != null);

        if (boot == null) {
            boot = startBot(config.token(), false, markovManager, markovConfig);
            markovAvailable = false;
        }

        final BootResult finalBoot = boot;
        Connect4CommandListener listener = boot.listener();
        JDA jda = boot.jda();
        listener.setMarkovAvailable(markovAvailable);
        listener.registerCommands(jda.updateCommands());
        System.out.println("Connect4 bot is online.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (finalBoot.markovListener() != null) {
                finalBoot.markovListener().shutdown();
            }
        }));
    }

    private static BootResult startBot(String token, boolean enableMessageContent,
                                       MarkovManager markovManager, MarkovConfig markovConfig)
            throws LoginException, InterruptedException {
        Connect4CommandListener listener = new Connect4CommandListener(enableMessageContent, markovManager, markovConfig);
        MarkovListener markovListener = null;

        if (enableMessageContent) {
            markovListener = new MarkovListener(markovManager, markovConfig, null);
        }

        StartupProbe probe = new StartupProbe();
        JDABuilder builder = JDABuilder.createDefault(token)
                .addEventListeners(listener, probe);

        if (markovListener != null) {
            builder.addEventListeners(markovListener);
        }

        if (enableMessageContent) {
            builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
        }

        JDA jda = builder.build();
        StartupOutcome outcome = probe.await(30, TimeUnit.SECONDS);

        if (outcome == StartupOutcome.DISALLOWED_INTENTS && enableMessageContent) {
            jda.shutdownNow();
            System.err.println("MESSAGE_CONTENT denied by Discord. Markov feature disabled, other features remain active.");
            return null;
        }

        if (outcome == StartupOutcome.SHUTDOWN) {
            throw new IllegalStateException("JDA shutdown during startup: " + probe.closeCode());
        }
        if (outcome == StartupOutcome.TIMEOUT) {
            throw new IllegalStateException("Timed out waiting for Discord startup.");
        }

        if (markovListener != null) {
            markovListener.setJDA(jda);
        }

        return new BootResult(jda, listener, markovListener);
    }

    private record BootResult(JDA jda, Connect4CommandListener listener, MarkovListener markovListener) {
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
