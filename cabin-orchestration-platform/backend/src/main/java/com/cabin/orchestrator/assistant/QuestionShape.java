package com.cabin.orchestrator.assistant;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A deterministic check that a request is shaped like a question about how something works, applied after the model has
 * spoken. The language model is the only thing deciding between "answer a question" and "propose an action", and it is
 * fallible: the first boundary run of the route's model sent "Can I turn the porch light on from here?" to
 * {@code set_device_state}. For the intents that would produce a proposal the route therefore asks the person instead of
 * trusting the model alone (W-117 boundary item, refocus order item 2).
 *
 * <p>This is a LEXICAL guard with a stated reach, not a guarantee about all questions. A request counts when, after
 * normalising it, any sentence starts (after skipping conversational openers and list markers, however many are stacked) with one of these lead words or phrases:
 * how, why, what, where, which, who, whose, whom, does, did, is, are, was, were, am, and "can/could/may/might/should/shall/
 * must/do/does" followed by I, we, someone, anyone, a guest or "the X". Anything else is not caught (see the PR for the forms
 * still missed). It fails closed in the direction that matters: it only ever withholds a proposal and asks.
 *
 * <p>Normalisation (so a quote, a greeting or a look-alike letter does not slip past): Unicode NFKC (fullwidth letters become
 * ASCII), invisible format characters (zero-width space and joiner, soft hyphen) are removed, common Cyrillic and Greek
 * look-alikes of Latin letters are folded, apostrophe variants are dropped, any Unicode whitespace is one space, and leading
 * characters that are not letters or digits (quotes, brackets, bullets, emoji) are stripped. "When" is deliberately not a lead
 * word: "when the front door opens, turn the heater on" is an automation. A trailing question mark alone does not count, and
 * neither do the polite requests "can you", "could you", "would you", "will you" and "please", which stay commands.
 */
final class QuestionShape {

    private static final Set<String> LEAD_WORDS = Set.of(
        "how", "why", "what", "whats", "where", "wheres", "which", "who", "whose", "whom",
        "does", "did", "is", "isnt", "are", "arent", "was", "wasnt", "were", "werent", "am", "doesnt", "didnt", "hows", "whos", "whys");

    /** First-person or impersonal ability and permission questions: "can I", "should we", "do I". */
    private static final Pattern LEAD_PHRASE = Pattern.compile(
        "^(can|could|may|might|should|shall|must|do|does)\\s+(i|we|someone|anyone|a\\s+guest|the\\s+\\w+)\\b");

    /** Conversational openers skipped before the check, longest first so "thank you" wins over "thank". */
    private static final List<String> OPENERS = List.of(
        "quick question", "i was wondering", "just wondering", "tiny helpdesk", "thank you", "help desk", "helpdesk",
        "can you tell me", "could you tell me", "i want to know", "i need to know", "id like to know", "excuse me",
        "good morning", "good afternoon", "good evening", "by the way", "tell me", "question", "hello", "thanks", "please",
        "pls", "thx", "sorry", "hiya", "hey", "hmm", "yo", "hi", "okay", "ok", "alright", "sure", "actually", "btw", "also",
        "and", "but", "then", "now", "so", "well", "um", "uh", "oh", "ask", "ahoy");

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\u00AD]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern LEADING_JUNK = Pattern.compile("^[^\\p{L}\\p{N}]+");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("[.!?;:\\n\\r]+");
    /** A list marker such as "1)", "a)" or "2]" left after the leading brackets are stripped. ("1." is split off as a sentence.) */
    private static final Pattern LIST_MARKER = Pattern.compile("^(\\d{1,3}|[a-z])[)\\]]\\s*");
    private static final Pattern OPENER_PUNCTUATION = Pattern.compile("^[\\s,;:!.\\-–—]+");

    /** Look-alike letters from Cyrillic and Greek that NFKC leaves alone, folded to the Latin letter they imitate. */
    private static final Map<Character, Character> LOOKALIKES = Map.ofEntries(
        Map.entry('а', 'a'), Map.entry('е', 'e'), Map.entry('о', 'o'), Map.entry('р', 'p'),
        Map.entry('с', 'c'), Map.entry('х', 'x'), Map.entry('у', 'y'), Map.entry('і', 'i'),
        Map.entry('ѕ', 's'), Map.entry('һ', 'h'), Map.entry('ј', 'j'), Map.entry('ӏ', 'l'),
        Map.entry('ο', 'o'), Map.entry('α', 'a'), Map.entry('ι', 'i'), Map.entry('υ', 'u'));

    private QuestionShape() {}

    static boolean isQuestion(String text) {
        if (text == null) return false;
        String t = normalise(text);
        if (t.isEmpty()) return false;
        // Any sentence may be the question ("Thanks. How do I turn it off?"), so test the start of each one.
        for (String sentence : SENTENCE_BREAK.split(t)) {
            if (startsLikeAQuestion(sentence)) return true;
        }
        return false;
    }

    static String normalise(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        t = INVISIBLE.matcher(t).replaceAll("");
        t = t.toLowerCase(Locale.ROOT);
        StringBuilder folded = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            Character replacement = LOOKALIKES.get(c);
            folded.append(replacement != null ? replacement : c);
        }
        t = folded.toString()
            .replace("’", "").replace("‘", "").replace("ʼ", "").replace("`", "").replace("´", "").replace("'", "");
        t = WHITESPACE.matcher(t).replaceAll(" ").strip();
        return t;
    }

    private static boolean startsLikeAQuestion(String sentence) {
        String s = LEADING_JUNK.matcher(WHITESPACE.matcher(sentence).replaceAll(" ").strip()).replaceFirst("");
        // Every pass strictly shortens the text, so this ends; it strips as many openers and markers as are stacked.
        while (true) {
            String stripped = LIST_MARKER.matcher(stripOpener(s)).replaceFirst("");
            if (stripped.equals(s)) break;
            s = LEADING_JUNK.matcher(stripped).replaceFirst("");
        }
        if (s.isEmpty()) return false;
        String first = s.split("[\\s,;:?!.]+", 2)[0];
        return LEAD_WORDS.contains(first) || LEAD_PHRASE.matcher(s).find();
    }

    private static String stripOpener(String s) {
        for (String opener : OPENERS) {
            if (s.startsWith(opener) && (s.length() == opener.length() || !Character.isLetterOrDigit(s.charAt(opener.length())))) {
                return OPENER_PUNCTUATION.matcher(s.substring(opener.length())).replaceFirst("").strip();
            }
        }
        return s;
    }
}
