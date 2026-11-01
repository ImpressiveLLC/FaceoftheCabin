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
 * how, why, what, where, which, who, whose, whom, whether, does, did, is, are, was, were, am, and "can/could/may/might/should/shall/
 * must/do/does" followed by I, we, someone, anyone, a guest or "the X". Anything else is not caught (see the PR for the forms
 * still missed). It fails closed in the direction that matters: it only ever withholds a proposal and asks.
 *
 * <p>A polite wrapper about asking ("can I ask", "could we ask", "am I able to ask", "is it ok to ask", with an optional "you" and
 * "a question" / "something" / "anything") is skipped, because the can/could/may of such a wrapper is not part of the inquiry
 * (Nate, 2026-11-01), and what follows is judged as it stands. After a wrapper, besides the lead words above, "when", "if",
 * "about", "for", "regarding" and "to explain / tell / show / describe / clarify" count as an inquiry ("can I ask when the heater
 * turns on", "can I ask you to explain how it works"). A wrapper followed by a request ("can I ask you to turn on the heater")
 * is a request, not a question. A wrapper standing alone ("can I ask a question?", "am I able to ask") is itself a question,
 * answered rhetorically.
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
        "does", "did", "is", "isnt", "are", "arent", "was", "wasnt", "were", "werent", "am", "doesnt", "didnt", "hows", "whos", "whys", "whether");

    /** First-person or impersonal ability and permission questions: "can I", "should we", "do I". */
    private static final Pattern LEAD_PHRASE = Pattern.compile(
        "^(can|could|may|might|should|shall|must|do|does)\\s+(i|we|someone|anyone|a\\s+guest|the\\s+\\w+)\\b");

    /**
     * A polite wrapper about asking ("can I ask", "could we ask", "am I able to ask", "is it ok to ask"), with an optional "you" and
     * "a question" / "something". Nate's rule (2026-11-01): the can/could/may of such a wrapper is not part of the inquiry. The
     * wrapper is skipped and what follows is judged as it stands ("can I ask how the heater works" is a question; "can I ask you to
     * turn on the heater" is a request and is not); a wrapper standing alone ("can I ask a question?", "am I able to ask") is itself a
     * question, answered rhetorically: you can ask, and I will do my best.
     */
    private static final String ASK_WRAPPER_BODY =
        "(?:(?:can|could|may|might)\\s+(?:i|we)\\s+(?:ask|inquire|enquire)|(?:am\\s+i|are\\s+we)\\s+able\\s+to\\s+(?:ask|inquire|enquire)"
        + "|is\\s+it\\s+(?:ok|okay)\\s+(?:to|if\\s+(?:i|we))\\s+(?:ask|inquire|enquire))"
        + "(?:\\s+(?:you|us))?(?:\\s+(?:a|one)(?:\\s+quick)?\\s+question|\\s+something|\\s+anything)?(?![a-z0-9])";
    private static final Pattern ASK_WRAPPER = Pattern.compile("^" + ASK_WRAPPER_BODY);

    /** The wrapper at the start of the ORIGINAL text (any case), after a greeting or two: "Hi, can I ask ...". */
    private static final Pattern WRAPPER_AT_START = Pattern.compile(
        "^\\W*(?:(?:hi|hello|hey|please|ok|okay|so|well|um|uh)\\b[\\s,!.]*)*" + ASK_WRAPPER_BODY, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AFTER_WRAPPER_FILLER = Pattern.compile("^[\\s,;:\\-–—]*(?:for\\s+)?(?:you\\s+)?(?:to\\s+)?", Pattern.CASE_INSENSITIVE);

    /**
     * What a request means once its politeness wrapper is taken off (W-140, Nate 2026-11-01): the wrapper is not the request, so what
     * follows is answered as it stands, even a fragment ("can I ask weather" is "weather"; "can I ask for you to turn on the heater" is
     * "turn on the heater"). {@code wrapped} is false when the text does not start with a wrapper; a wrapper with nothing after it
     * ("may I inquire something") has a blank remainder.
     */
    record Unwrapped(boolean wrapped, String remainder) {}

    static Unwrapped unwrap(String text) {
        if (text == null) return new Unwrapped(false, "");
        java.util.regex.Matcher m = WRAPPER_AT_START.matcher(text.strip());
        if (!m.find()) return new Unwrapped(false, text);
        String rest = text.strip().substring(m.end());
        rest = AFTER_WRAPPER_FILLER.matcher(rest).replaceFirst("").strip();
        // a bare question mark or full stop after the wrapper is not a remainder
        String bare = rest.replaceAll("[\\s?!.,;:]+", "");
        return new Unwrapped(true, bare.isEmpty() ? "" : rest);
    }

    /** Conversational openers skipped before the check, longest first so "thank you" wins over "thank". */
    private static final List<String> OPENERS = List.of(
        // multi-word forms first, so "i have a question about" wins over "i"
        // the "can/could/may i ask ..." wrappers are handled by ASK_WRAPPER (see its note), not listed here
        "quick question", "one more question", "another question", "next question", "my question is", "i have a question about",
        "i have a question", "i had a question", "i would like to ask", "id like to ask",
        "i was wondering", "just wondering", "i wonder", "just curious", "curious", "wondering", "one more thing", "quick one",
        "tiny helpdesk", "thank you", "thanks a lot", "many thanks", "help desk", "helpdesk", "ask the helpdesk", "hey there",
        "hi there", "hello there", "good day", "good morning", "good afternoon", "good evening", "dear sir or madam", "dear sir",
        "can you tell me", "could you tell me", "would you tell me", "can you explain", "could you explain", "i want to know",
        "i need to know", "id like to know", "i would like to know", "excuse me", "by the way", "anyway", "tell me",
        "question", "hello", "thanks", "please", "kindly", "pls", "thx", "ty", "cheers", "sorry", "hiya", "hey", "hmm", "hmmm",
        "yo", "hi", "okay", "ok", "alright", "sure", "right", "yes", "yeah", "yep", "no", "nope", "like", "actually", "btw",
        "also", "and", "but", "then", "now", "so", "well", "um", "uh", "er", "oh", "ah", "ask", "assistant", "bot", "claude",
        "ahoy");

    /** What counts as an inquiry right after a wrapper, beyond the lead words: "can I ask if ...", "about ...", "you to explain how ...". */
    private static final Pattern AFTER_WRAPPER = Pattern.compile(
        "^(?:when|if|about|for|regarding|to\\s+(?:explain|tell|show|describe|clarify))(?![a-z0-9])");

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\u00AD\\uFE0F\\u20E3]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern LEADING_JUNK = Pattern.compile("^[^\\p{L}\\p{N}]+");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("[.!?;:\\n\\r]+");
    /** A list marker such as "1)", "a)", "iv)" or "2]" left after the leading brackets are stripped. ("1." is split off as a sentence.)
     *  Only a digit, one letter or a roman numeral: a short question word ("is", "do", "how") followed by a dash or bracket is not a marker. */
    private static final Pattern LIST_MARKER = Pattern.compile(
        "^(?:(?:step|no|number|item|q|question)\\.?\\s*)?#?(?:\\d{1,3}|[a-z]|[ivx]{2,4})\\s*[)\\]}>\\-–—]+\\s*"
        + "|^#\\d{1,3}\\s+|^\\d{1,3}\\s+(?=[a-z])");
    /** One to three letters or digits and a closing bracket at the start: "id) how ...". */
    private static final Pattern LOOSE_TAG = Pattern.compile("^[a-z0-9]{1,3}[)\\]}>]+\\s*");
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
        boolean wrapperSeen = false;
        // Every pass strictly shortens the text, so this ends; it strips as many wrappers, openers and markers as are stacked.
        while (true) {
            java.util.regex.Matcher wrapper = ASK_WRAPPER.matcher(s);
            String next;
            if (wrapper.find()) {
                wrapperSeen = true;
                next = OPENER_PUNCTUATION.matcher(s.substring(wrapper.end())).replaceFirst("").strip();
            } else {
                next = LIST_MARKER.matcher(stripOpener(s)).replaceFirst("");
            }
            if (next.equals(s)) break;
            s = LEADING_JUNK.matcher(next).replaceFirst("");
        }
        // Nothing left after a wrapper ("can I ask a question?", "am I able to ask"): a question about asking, answered rhetorically.
        if (s.isEmpty()) return wrapperSeen;
        if (leadsWithAQuestion(s, wrapperSeen)) return true;
        // "id) how does it work": a short tag and a bracket in front of the question. The tag is dropped for this second look only, so a
        // question word that merely sits next to a bracket ("do) I need a token") is still judged as written by the first.
        String untagged = LOOSE_TAG.matcher(s).replaceFirst("");
        return !untagged.equals(s) && leadsWithAQuestion(untagged, wrapperSeen);
    }

    private static boolean leadsWithAQuestion(String s, boolean wrapperSeen) {
        // The first word ends at a space, punctuation, a dash or a closing bracket: "how- do I turn it off" starts with "how".
        String first = s.split("[\\s,;:?!.\\-–—)\\]}>]+", 2)[0];
        return LEAD_WORDS.contains(first) || LEAD_PHRASE.matcher(s).find() || (wrapperSeen && AFTER_WRAPPER.matcher(s).find());
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
