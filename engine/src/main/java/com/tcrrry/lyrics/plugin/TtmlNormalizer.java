package com.tcrrry.lyrics.plugin;

import java.util.regex.*;

/** Text edits leave the native document, metadata, namespaces and timing untouched. */
public final class TtmlNormalizer {
    private static final Pattern SPAN = Pattern.compile("(?is)(<span\\b[^>]*>)([^<]*)(</span\\s*>)");
    public static String spaces(String input) {
        if (input == null || input.length() > 524288) return input;
        Matcher matcher = SPAN.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String text = matcher.group(2);
            int first = 0, last = text.length();
            while (first < last && Character.isWhitespace(text.charAt(first))) first++;
            while (last > first && Character.isWhitespace(text.charAt(last - 1))) last--;
            String replacement = first == text.length() ? text : text.substring(0, first) + matcher.group(1)
                    + text.substring(first, last) + matcher.group(3) + text.substring(last);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
    private static final Pattern PAIR = Pattern.compile("(?is)<span begin=\"([^\"]+)\" end=\"([^\"]+)\">([^<]+)</span><span begin=\"([^\"]+)\" end=\"([^\"]+)\">([^<]+)</span>");
    private static long time(String text) {
        try {
            if (text.endsWith("ms")) return Math.round(Double.parseDouble(text.substring(0, text.length()-2)));
            if (text.endsWith("s")) return Math.round(Double.parseDouble(text.substring(0, text.length()-1)) * 1000);
            String[] fields = text.split(":"); double value = 0;
            for (String field : fields) value = value * 60 + Double.parseDouble(field);
            return Math.round(value * 1000);
        } catch (NumberFormatException e) { return -1; }
    }
    private static boolean glyphs(String text) {
        for (int i=0; i<text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c < 128 || c == '&') return false;
        }
        return true;
    }
    /** Deliberately narrow: bare adjacent spans, same paragraph, no agent/role attributes. */
    public static String smooth(String input) {
        if (input == null || input.length() > 524288) return input;
        // A paragraph agent/role may denote a separate background voice.
        if (Pattern.compile("(?is)<p\\b[^>]*(?:role|agent)\\s*=").matcher(input).find()) return input;
        for (int pass=0; pass<32; pass++) {
            Matcher m = PAIR.matcher(input); StringBuffer out = new StringBuffer(); boolean changed = false;
            while (m.find()) {
                long a=time(m.group(1)), b=time(m.group(2)), c=time(m.group(4)), d=time(m.group(5));
                if (a>=0 && b>a && c>=a && d>c && c<=b+20 && glyphs(m.group(3)+m.group(6)) &&
                        ((b-a<100) || (d-c<100))) {
                    m.appendReplacement(out, Matcher.quoteReplacement("<span begin=\""+m.group(1)+"\" end=\""+
                        (b>d?m.group(2):m.group(5))+"\">"+m.group(3)+m.group(6)+"</span>"));
                    changed=true;
                } else m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
            m.appendTail(out); input=out.toString(); if (!changed) break;
        }
        return input;
    }
}
