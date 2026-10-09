package com.masteroebot.bot;

import com.masteroebot.masteroebot.MasterOEBotProfile;
import com.masteroebot.paraokabot.ParaokaBotProfile;
import java.util.List;
import java.util.Set;

/**
 * Bot definitions. Each bot owns its slash commands on its own application:
 * MasterOEBot the full set, paraokabot only its own toggle. Per-channel
 * enable is independent per bot.
 */
public final class BotRegistry {
    private BotRegistry() {
    }

    public static final BotProfile MasterOEBot = MasterOEBotProfile.PROFILE;

    public static final BotProfile PARAOKA = ParaokaBotProfile.PROFILE;

    /** Known bots in token order. Token index i pairs with PROFILES[i]. */
    public static final List<BotProfile> PROFILES = List.of(MasterOEBot, PARAOKA);

    /** Every bot log tag, for shared-log scrub detection. */
    public static final Set<String> ALL_TAGS = Set.of(MasterOEBot.botTag().trim(), PARAOKA.botTag().trim());
}
