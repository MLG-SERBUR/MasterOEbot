package com.masteroebot.connect4;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;

import com.masteroebot.markov.ArliAiCoordinator;
import com.masteroebot.markov.ArliAiReactionResponder;
import com.masteroebot.markov.ArliAiSecondChanceResponder;
import com.masteroebot.markov.MarkovConfig;
import com.masteroebot.markov.MarkovListener;
import com.masteroebot.markov.MarkovManager;
import com.masteroebot.markov.RoundRobinGenerativeAiResponder;
import com.masteroebot.typeracer.TypeRacerCommandListener;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class BotMain {
    public static void main(String[] args) {
        try {
            runBot(args);
        } catch (Throwable t) {
            // An unhandled startup failure (e.g. DNS not yet available at boot)
            // kills only the main thread. JDA/OkHttp leave non-daemon worker
            // threads behind, so the JVM would hang forever as a zombie process
            // and systemd would never see an exit, meaning Restart=always would
            // never fire. Exit explicitly so the service manager restarts us.
            t.printStackTrace();
            System.err.println("MasterOEbot failed to start. Exiting so the service manager can restart us.");
            System.exit(1);
        }
    }

    private static void runBot(String[] args) throws LoginException, InterruptedException, IOException {
        if (args.length > 0 && args[0].equals("--scrub")) {
            com.masteroebot.markov.BrainScrubber.main(args);
            return;
        }
        if (args.length > 0 && args[0].equals("--scrub-ai")) {
            com.masteroebot.markov.AiLogScrubber.main(args);
            return;
        }
        if (args.length > 0 && args[0].equals("--scrubboth")) {
            com.masteroebot.markov.BothScrubber.main(args);
            return;
        }
        if (args.length > 0 && args[0].equals("--scrubuserurls")) {
            com.masteroebot.markov.UserUrlScrubber.main(args);
            return;
        }
        Path configPath = Path.of("config.yaml");
        BotConfig config = BotConfig.load(configPath);

        MarkovConfig markovConfig = new MarkovConfig();
        markovConfig.load();
        MarkovManager markovManager = new MarkovManager(markovConfig);

        RoundRobinGenerativeAiResponder generativeAiResponder =
                new RoundRobinGenerativeAiResponder(config.generativeAiConfig());
        ArliAiCoordinator coordinator = new ArliAiCoordinator();
        ArliAiReactionResponder reactionResponder =
                new ArliAiReactionResponder(config.generativeAiConfig(), coordinator);
        ArliAiSecondChanceResponder secondChanceResponder =
                new ArliAiSecondChanceResponder(config.generativeAiConfig(), coordinator);
        System.out.println("Loaded system prompt: " + config.generativeAiConfig().systemPrompt());

        // Retry startup so a transient outage (e.g. DNS not reachable yet right
        // after boot) doesn't take the bot down for good. Network errors surface
        // here as runtime exceptions (JDA wraps UnknownHostException in
        // ErrorResponseException). InterruptedException is never retried.
        BootResult boot = null;
        final int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                boot = startBot(config.token(), true, markovManager, markovConfig, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator);
                break;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (LoginException | RuntimeException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                System.err.println("Startup attempt " + attempt + "/" + maxAttempts + " failed: " + e);
                System.err.println("Retrying in 15 seconds...");
                Thread.sleep(15_000);
            }
        }
        boolean markovAvailable = (boot != null && boot.markovListener() != null);

        if (boot == null) {
            // DISALLOWED_INTENTS: retrying with MESSAGE_CONTENT enabled is
            // pointless, so this second attempt is not retried on failure.
            boot = startBot(config.token(), false, markovManager, markovConfig, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator);
            markovAvailable = false;
        }

        final BootResult finalBoot = boot;
        Connect4CommandListener listener = boot.listener();
        TypeRacerCommandListener typeracerListener = boot.typeracerListener();
        JDA jda = boot.jda();
        listener.setMarkovAvailable(markovAvailable);
        // Single updateCommands() call: each updateCommands() replaces ALL
        // global commands, so registering listeners separately wipes others.
        net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction commandUpdater = jda.updateCommands();
        listener.registerCommands(commandUpdater);
        typeracerListener.registerCommands(commandUpdater);
        commandUpdater.queue(
                success -> System.out.println("Registered slash commands."),
                error -> System.err.println("Slash command registration failed. " + error.getMessage())
        );
        System.out.println("Connect4 bot is online.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (finalBoot.markovListener() != null) {
                finalBoot.markovListener().shutdown();
            }
        }));
    }

    private static BootResult startBot(String token, boolean enableMessageContent,
                                       MarkovManager markovManager, MarkovConfig markovConfig,
                                       RoundRobinGenerativeAiResponder generativeAiResponder,
                                       ArliAiReactionResponder reactionResponder,
                                       ArliAiSecondChanceResponder secondChanceResponder,
                                       ArliAiCoordinator coordinator)
            throws LoginException, InterruptedException {
        Connect4CommandListener listener =
                new Connect4CommandListener(enableMessageContent, markovManager, markovConfig, generativeAiResponder);
        TypeRacerCommandListener typeracerListener = new TypeRacerCommandListener(enableMessageContent);
        MarkovListener markovListener = null;

        if (enableMessageContent) {
            markovListener = new MarkovListener(markovManager, markovConfig, null, generativeAiResponder, reactionResponder, secondChanceResponder, coordinator);
        }

        StartupProbe probe = new StartupProbe();
        JDABuilder builder = JDABuilder.createDefault(token)
                .addEventListeners(listener, typeracerListener, probe);

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

        return new BootResult(jda, listener, typeracerListener, markovListener);
    }

    private record BootResult(JDA jda, Connect4CommandListener listener, TypeRacerCommandListener typeracerListener, MarkovListener markovListener) {
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
