package com.masteroebot.markov;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ProfanityFilter {
    private static final List<String> BAD_WORDS = new ArrayList<>();
    static {
        // User can add more words here
        BAD_WORDS.add("fuck");
    }

    private static Pattern pattern;

    static {
        updatePattern();
    }

    private static void updatePattern() {
        if (BAD_WORDS.isEmpty()) {
            pattern = Pattern.compile("(?!)"); // Matches nothing
            return;
        }
        StringBuilder sb = new StringBuilder("\\b(");
        for (int i = 0; i < BAD_WORDS.size(); i++) {
            sb.append(Pattern.quote(BAD_WORDS.get(i)));
            if (i < BAD_WORDS.size() - 1) {
                sb.append("|");
            }
        }
        sb.append(")\\b");
        pattern = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    public static boolean containsProfanity(String text) {
        if (text == null || text.isEmpty()) return false;
        return pattern.matcher(text).find();
    }

    public static List<String> getBadWords() {
        return BAD_WORDS;
    }
}
