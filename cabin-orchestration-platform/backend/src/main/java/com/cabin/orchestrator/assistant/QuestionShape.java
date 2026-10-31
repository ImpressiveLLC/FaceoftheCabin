package com.cabin.orchestrator.assistant;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A deterministic check that a request is shaped like a question about how something works, applied after the model has
 * spoken. The language model is the only thing deciding between "answer a question" and "propose an action", and it is
 * fallible: the first boundary run of the route's model sent "Can I turn the porch light on from here?" to
 * {@code set_device_state}. A question must never become a proposal, so for the intents that would produce one the route
 * asks the person instead of trusting the model alone (W-117 boundary item, refocus order item 2).
 *
 * <p>Deliberately narrow, so it costs little: only an interrogative LEAD counts (how, why, what, where, which, who,
 * does, is, are, "can I", "should I" and similar). A trailing question mark alone does not, and neither do the polite
 * requests "can you", "could you", "would you", "will you" and "please", which are commands to the assistant and stay commands.
 * It is never used to turn a command into something it can do; it only withholds a proposal and asks.
 */
final class QuestionShape {

    private static final Set<String> LEAD_WORDS = Set.of(
        "how", "why", "what", "whats", "where", "wheres", "which", "who", "whose", "whom",
        "does", "did", "is", "isnt", "are", "arent", "was", "were", "am");
    // "when" is left out on purpose: "when the front door opens, turn the heater on" is an automation, not a question.

    /** First-person or impersonal ability and permission questions: "can I", "should we", "do I". */
    private static final Pattern LEAD_PHRASE = Pattern.compile(
        "^(can|could|may|might|should|shall|must|do|does)\\s+(i|we|someone|anyone|a\\s+guest|the\\s+\\w+)\\b");

    private QuestionShape() {}

    static boolean isQuestion(String text) {
        if (text == null) return false;
        // Apostrophes are dropped so "what's", "what’s" and "isn't" read as whats and isnt.
        String t = text.strip().toLowerCase(Locale.ROOT).replace("’", "").replace("'", "");
        if (t.isEmpty()) return false;
        String first = t.split("[\\s,;:?!.]+", 2)[0];
        return LEAD_WORDS.contains(first) || LEAD_PHRASE.matcher(t).find();
    }
}
