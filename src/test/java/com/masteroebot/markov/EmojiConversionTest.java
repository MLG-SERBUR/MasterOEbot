package com.masteroebot.markov;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmojiConversionTest {
    private MarkovListener listener;

    @BeforeEach
    void setUp() {
        MarkovManager manager = new MarkovManager(new MarkovConfig(), null);
        listener = new MarkovListener(manager, new MarkovConfig(), null, new PlaceholderGenerativeAiResponder());
    }

    @Test
    void testStandardConversion() {
        RichCustomEmoji emoji = mockEmoji("smile", "12345");
        Guild guild = mockGuild("smile", emoji);

        String input = "Hello :smile: world";
        String expected = "Hello <:smile:12345> world";
        assertEquals(expected, listener.resolveGuildEmoji(guild, input));
    }

    @Test
    void testAlreadyConvertedShouldNotDoubleConvert() {
        RichCustomEmoji emoji = mockEmoji("smile", "12345");
        Guild guild = mockGuild("smile", emoji);

        String input = "Hello <:smile:12345> world";
        String expected = "Hello <:smile:12345> world";
        assertEquals(expected, listener.resolveGuildEmoji(guild, input));
    }

    @Test
    void testIncorrectIdShouldBeCorrected() {
        RichCustomEmoji emoji = mockEmoji("smile", "12345");
        Guild guild = mockGuild("smile", emoji);

        String input = "Hello <:smile:67890> world";
        String expected = "Hello <:smile:12345> world";
        assertEquals(expected, listener.resolveGuildEmoji(guild, input));
    }

    @Test
    void testNoIdButWithColonSuffixShouldBeCorrected() {
        RichCustomEmoji emoji = mockEmoji("smile", "12345");
        Guild guild = mockGuild("smile", emoji);

        String input = "Hello :smile:67890 world";
        String expected = "Hello <:smile:12345> world";
        assertEquals(expected, listener.resolveGuildEmoji(guild, input));
    }

    private RichCustomEmoji mockEmoji(String name, String id) {
        return (RichCustomEmoji) Proxy.newProxyInstance(
                RichCustomEmoji.class.getClassLoader(),
                new Class<?>[]{RichCustomEmoji.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return name;
                    if (method.getName().equals("getAsMention")) return "<:" + name + ":" + id + ">";
                    if (method.getName().equals("isAvailable")) return true;
                    return null;
                });
    }

    private Guild mockGuild(String emojiName, RichCustomEmoji emoji) {
        return (Guild) Proxy.newProxyInstance(
                Guild.class.getClassLoader(),
                new Class<?>[]{Guild.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getEmojisByName")) {
                        return args[0].equals(emojiName) ? List.of(emoji) : Collections.emptyList();
                    }
                    return null;
                });
    }
}
