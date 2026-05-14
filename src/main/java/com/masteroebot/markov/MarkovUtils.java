package com.masteroebot.markov;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkovUtils {
    private static final Pattern USER_MENTION_PATTERN = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE_MENTION_PATTERN = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL_MENTION_PATTERN = Pattern.compile("<#(\\d+)>");

    /**
     * Resolves mentions in the message content to their display names.
     * Prefers guild nicknames for user mentions.
     */
    public static String getDisplayNameContent(Message message) {
        if (message == null) return "";
        String content = message.getContentRaw();
        if (content == null || content.isEmpty()) return "";

        // 1. Resolve User Mentions to Nicknames
        Matcher userMatcher = USER_MENTION_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        while (userMatcher.find()) {
            sb.append(content, lastEnd, userMatcher.start());
            String idStr = userMatcher.group(1);
            try {
                long id = Long.parseLong(idStr);
                
                // Prioritize mentioned members (nicknames)
                Member member = message.getMentions().getMembers().stream()
                        .filter(m -> m.getIdLong() == id)
                        .findFirst()
                        .orElse(message.getGuild().getMemberById(id));
                
                if (member != null) {
                    sb.append("@").append(member.getEffectiveName());
                } else {
                    User user = message.getMentions().getUsers().stream()
                            .filter(u -> u.getIdLong() == id)
                            .findFirst()
                            .orElse(message.getJDA().getUserById(id));
                    if (user != null) {
                        sb.append("@").append(user.getEffectiveName());
                    } else {
                        sb.append(userMatcher.group());
                    }
                }
            } catch (Exception e) {
                sb.append(userMatcher.group());
            }
            lastEnd = userMatcher.end();
        }
        sb.append(content.substring(lastEnd));
        content = sb.toString();

        // 2. Resolve Role Mentions
        Matcher roleMatcher = ROLE_MENTION_PATTERN.matcher(content);
        sb = new StringBuilder();
        lastEnd = 0;
        while (roleMatcher.find()) {
            sb.append(content, lastEnd, roleMatcher.start());
            String idStr = roleMatcher.group(1);
            try {
                long id = Long.parseLong(idStr);
                Role role = message.getMentions().getRoles().stream()
                        .filter(r -> r.getIdLong() == id)
                        .findFirst()
                        .orElse(message.getGuild().getRoleById(id));
                if (role != null) {
                    sb.append("@").append(role.getName());
                } else {
                    sb.append(roleMatcher.group());
                }
            } catch (Exception e) {
                sb.append(roleMatcher.group());
            }
            lastEnd = roleMatcher.end();
        }
        sb.append(content.substring(lastEnd));
        content = sb.toString();

        // 3. Resolve Channel Mentions
        Matcher channelMatcher = CHANNEL_MENTION_PATTERN.matcher(content);
        sb = new StringBuilder();
        lastEnd = 0;
        while (channelMatcher.find()) {
            sb.append(content, lastEnd, channelMatcher.start());
            String idStr = channelMatcher.group(1);
            try {
                long id = Long.parseLong(idStr);
                GuildChannel channel = message.getMentions().getChannels().stream()
                        .filter(c -> c.getIdLong() == id)
                        .findFirst()
                        .orElse(message.getGuild().getGuildChannelById(id));
                if (channel != null) {
                    sb.append("#").append(channel.getName());
                } else {
                    sb.append(channelMatcher.group());
                }
            } catch (Exception e) {
                sb.append(channelMatcher.group());
            }
            lastEnd = channelMatcher.end();
        }
        sb.append(content.substring(lastEnd));
        
        return sb.toString();
    }
}
