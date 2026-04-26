package com.masteroebot.markov;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URL;
import java.util.*;

public class JMegaHal implements Serializable {

    public static final String WORD_CHARS = "abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
            "0123456789";
    public static final String END_CHARS = ".!?";

    public JMegaHal() {

    }

    public void addDocument(String uri) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new URL(uri).openStream()));
        StringBuffer buffer = new StringBuffer();
        int ch = 0;
        while ((ch = reader.read()) != -1) {
            buffer.append((char) ch);
            if (END_CHARS.indexOf((char) ch) >= 0) {
                String sentence = buffer.toString();
                sentence = sentence.replace('\r', ' ');
                sentence = sentence.replace('\n', ' ');
                add(sentence);
                buffer = new StringBuffer();
            }
        }
        add(buffer.toString());
        reader.close();
    }

    public void add(String sentence) {
        sentence = sentence.trim();
        ArrayList<String> parts = new ArrayList<>();
        char[] chars = sentence.toCharArray();
        int i = 0;
        boolean punctuation = false;
        StringBuffer buffer = new StringBuffer();
        while (i < chars.length) {
            char ch = chars[i];
            if ((WORD_CHARS.indexOf(ch) >= 0) == punctuation) {
                punctuation = !punctuation;
                String token = buffer.toString();
                if (token.length() > 0) {
                    parts.add(token);
                }
                buffer = new StringBuffer();
                continue;
            }
            buffer.append(ch);
            i++;
        }
        String lastToken = buffer.toString();
        if (lastToken.length() > 0) {
            parts.add(lastToken);
        }

        if (parts.size() >= 4) {
            for (i = 0; i < parts.size() - 3; i++) {
                Quad quad = new Quad(parts.get(i), parts.get(i + 1), parts.get(i + 2), parts.get(i + 3));
                if (quads.containsKey(quad)) {
                    quad = quads.get(quad);
                } else {
                    quads.put(quad, quad);
                }

                if (i == 0) {
                    quad.setCanStart(true);
                }
                if (i == parts.size() - 4) {
                    quad.setCanEnd(true);
                }

                for (int n = 0; n < 4; n++) {
                    String token = parts.get(i + n);
                    if (!words.containsKey(token)) {
                        words.put(token, new HashSet<>(1));
                    }
                    HashSet<Quad> set = words.get(token);
                    set.add(quad);
                }

                if (i > 0) {
                    String previousToken = parts.get(i - 1);
                    if (!previous.containsKey(quad)) {
                        previous.put(quad, new HashSet<>(1));
                    }
                    HashSet<String> set = previous.get(quad);
                    set.add(previousToken);
                }

                if (i < parts.size() - 4) {
                    String nextToken = parts.get(i + 4);
                    if (!next.containsKey(quad)) {
                        next.put(quad, new HashSet<>(1));
                    }
                    HashSet<String> set = next.get(quad);
                    set.add(nextToken);
                }

            }
        }
    }

    public String getSentence() {
        return getSentence(null);
    }

    public String getSentence(String word) {
        LinkedList<String> parts = new LinkedList<>();

        Quad[] quads;
        if (word != null && words.containsKey(word)) {
            quads = words.get(word).toArray(new Quad[0]);
        } else {
            quads = this.quads.keySet().toArray(new Quad[0]);
        }

        if (quads.length == 0) {
            return "";
        }

        Quad middleQuad = quads[rand.nextInt(quads.length)];
        Quad quad = middleQuad;

        for (int i = 0; i < 4; i++) {
            parts.add(quad.getToken(i));
        }

        while (quad.canEnd() == false) {
            String[] nextTokens = next.get(quad).toArray(new String[0]);
            String nextToken = nextTokens[rand.nextInt(nextTokens.length)];
            quad = this.quads.get(new Quad(quad.getToken(1), quad.getToken(2), quad.getToken(3), nextToken));
            parts.add(nextToken);
        }

        quad = middleQuad;
        while (quad.canStart() == false) {
            String[] previousTokens = previous.get(quad).toArray(new String[0]);
            String previousToken = previousTokens[rand.nextInt(previousTokens.length)];
            quad = this.quads.get(new Quad(previousToken, quad.getToken(0), quad.getToken(1), quad.getToken(2)));
            parts.addFirst(previousToken);
        }

        StringBuffer sentence = new StringBuffer();
        Iterator<String> it = parts.iterator();
        while (it.hasNext()) {
            String token = it.next();
            sentence.append(token);
        }

        return sentence.toString();
    }

    private final HashMap<String, HashSet<Quad>> words = new HashMap<>();
    private final HashMap<Quad, Quad> quads = new HashMap<>();
    private final HashMap<Quad, HashSet<String>> next = new HashMap<>();
    private final HashMap<Quad, HashSet<String>> previous = new HashMap<>();
    private final Random rand = new Random();

}
