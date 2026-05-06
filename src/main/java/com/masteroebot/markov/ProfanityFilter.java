package com.masteroebot.markov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProfanityFilter {
    private static final List<String> BAD_WORDS = new ArrayList<>();
    
    private static volatile Pattern pattern;

    static {
        addWord("fuck");
    }

    private static synchronized void updatePattern() {
        if (BAD_WORDS.isEmpty()) {
            pattern = Pattern.compile("(?!)");
            return;
        }
        
        String regex = BAD_WORDS.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|", "\\b(", ")\\b"));
                
        pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public static synchronized void addWord(String word) {
        if (word != null && !word.isEmpty() && !BAD_WORDS.contains(word.toLowerCase())) {
            BAD_WORDS.add(word.toLowerCase());
            updatePattern();
        }
    }

    public static synchronized void removeWord(String word) {
        if (word != null && BAD_WORDS.remove(word.toLowerCase())) {
            updatePattern();
        }
    }

    public static boolean containsProfanity(String text) {
        if (text == null || text.isEmpty()) return false;
        
        Pattern currentPattern = pattern; 
        return currentPattern.matcher(text).find();
    }

    public static List<String> getBadWords() {
        return Collections.unmodifiableList(BAD_WORDS);
    }
}
