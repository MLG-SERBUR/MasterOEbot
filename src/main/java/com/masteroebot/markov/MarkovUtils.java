package com.masteroebot.markov;

import net.dv8tion.jda.api.entities.Message;

public class MarkovUtils {

    /**
     * Resolves mentions in the message content to their display names.
     * Prefers guild nicknames for user mentions.
     */
    public static String getDisplayNameContent(Message message) {
        if (message == null) {
            return "";
        }
        return message.getContentDisplay();
    }
}
