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
        addWord("shit");
        addWord("damn");
    }

    private static synchronized void updatePattern() {
        if (BAD_WORDS.isEmpty()) {
            pattern = Pattern.compile("(?!)");
            return;
        }
        
        // Whitelist of common compound prefixes (catches bullshit, motherfucker, dumbass)
        String prefixes = "(?:bull|horse|dog|cow|pig|bat|ape|chicken|dip|dumb|jack|mother|cluster|mind|un|holy)?";
        
        // Whitelist of common compound suffixes (catches asshole, shithead, fucktard)
        String suffixes = "(?:ings?|ers?|ed|es|s|y|head|hole|ass|bag|face|wit|stick|stain|weed|tards?)?";
        
        String regex = BAD_WORDS.stream()
                .map(word -> {
                    StringBuilder patternBuilder = new StringBuilder();
                    for (char c : word.toCharArray()) {
                        patternBuilder.append(Pattern.quote(String.valueOf(c))).append("+");
                    }
                    return patternBuilder.toString();
                })
                .collect(Collectors.joining(
                        "|", 
                        "\\b" + prefixes + "(?:", 
                        ")" + suffixes + "(?:(?-i:(?<=[a-z])(?=[A-Z]))|\\b)"
                ));
                
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
