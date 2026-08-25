package com.masteroebot.connect4;

import com.masteroebot.markov.GenerativeAiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BotConfigTest {

    @TempDir
    Path tempDir;

    private Path writeConfig(String aiSection) throws IOException {
        Path path = tempDir.resolve("config.yaml");
        Files.writeString(path, """
                discord:
                  token: "test-token"
                ai:
                %s
                """.formatted(aiSection));
        return path;
    }

    @Test
    void emptyCerebrasModelsStaysEmpty() throws IOException {
        BotConfig config = BotConfig.load(writeConfig("""
                    cerebrasApiKey: "ck"
                    groqApiKey: "gk"
                    cerebrasModels: []
                    groqModels:
                      - "gm"
                """));

        assertEquals(List.of(), config.generativeAiConfig().cerebrasModels());
    }

    @Test
    void missingModelListsFallBackToDefaults() throws IOException {
        BotConfig config = BotConfig.load(writeConfig("""
                    groqApiKey: "gk"
                """));

        GenerativeAiConfig defaults = GenerativeAiConfig.defaults();
        assertEquals(defaults.cerebrasModels(), config.generativeAiConfig().cerebrasModels());
        assertEquals(defaults.groqModels(), config.generativeAiConfig().groqModels());
    }
}
