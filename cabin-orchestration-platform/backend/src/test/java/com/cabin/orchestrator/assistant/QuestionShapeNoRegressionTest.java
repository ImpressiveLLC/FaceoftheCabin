package com.cabin.orchestrator.assistant;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression pin for the question guard: widening the opener and list-marker patterns (#377) stopped some forms that were
 * recognised before from being recognised. QuestionShapePre377 is the old guard kept as an oracle; this test builds many
 * question forms (prefixes x cores) and requires that anything the old guard recognised is still recognised. The guard only
 * ever withholds a proposal, so recognising more is harmless; recognising less is the regression.
 */
class QuestionShapeNoRegressionTest {

    private static final List<String> PREFIXES = List.of(
        "", "  ", "\"", "(", "- ", "* ", "> ", "1) ", "a) ", "[2] ", "#3 ", "#3) ", "Step 2) ", "No. 4: ", "iv) ", "1 - ", "2 ",
        "Hi, ", "Hey there, ", "Hello! ", "Please, ", "Thanks. ", "Quick question: ", "Ok so ", "Well, ", "Um, ", "And ", "But ", "Now ",
        "Tell me ", "Can you tell me ", "I need to know: ", "I was wondering ", "Sorry, ", "Yes, ", "Anyway, ");

    private static final List<String> CORES = List.of(
        // lead words
        "how do I turn off the heater?", "why is the valve closed", "what does Fire do on a workflow?", "where is the token kept",
        "which sensors are offline", "who can silence the siren", "does the heater turn on by itself", "did my workflow run",
        "is the porch light on", "isn't the valve closed", "are the sensors online", "was the door opened", "were the alerts sent",
        "am I allowed to remove a device", "whats the difference between Watch and Alert", "hows the backend doing",
        // lead phrases
        "can I turn the porch light on from here?", "could I silence the siren", "may I remove a device", "might we reuse a token",
        "should we restart the backend", "shall I add a device", "must I sign in again", "do I need a token", "does the heater need a plug",
        "can someone silence the siren", "can anyone add a device", "can a guest see the cameras", "can the heater be scheduled",
        // forms that begin with a short question word followed by a marker-like character
        "is-it on", "is - it on", "what-is the heater doing", "who) can silence the siren", "do) I need a token", "how- do I turn it off",
        "is it on", "do i", "can i", "can i ask how the heater works", "can i ask: why is the valve closed",
        "whom- do I ask", "id) how does it work", "who] can silence the siren", "how> do I turn it off", "what}is the heater doing",
        // "ask"-style capability questions
        "can I ask you to turn on the heater?", "could I ask whether the valve is closed", "may I ask who can silence the siren",
        "can i ask you something about the heater", "could i ask what the heater does",
        // wrapper followed by an inquiry that is not a lead word: judged by what follows (Nate, 2026-11-01)
        "can i ask if the valve is closed", "could i ask about the heater", "may i ask for help with the heater",
        "can i ask a question about the heater", "can i ask something about the heater", "can i ask a quick question about the heater",
        "can i ask you to explain how the heater works", "can i ask when the heater turns on", "might i ask which sensors are offline",
        "can we ask whether the valve is closed", "am i able to ask how to add a device", "is it ok to ask who can see the cameras");

    @Test
    void everyFormTheOldGuardRecognisedIsStillRecognised() {
        List<String> lost = new ArrayList<>();
        for (String prefix : PREFIXES) {
            for (String core : CORES) {
                String input = prefix + core;
                if (isRequestAfterWrapper(input)) continue; // Nate's rule 2026-11-01: a wrapper followed by a request is a request
                if (QuestionShapePre377.isQuestion(input) && !QuestionShape.isQuestion(input)) lost.add(input);
            }
        }
        assertThat(lost).as("forms recognised before #377 and not now").isEmpty();
    }

    /**
     * The only forms exempt from the comparison: an ask wrapper followed by "you to" and then something other than explain, tell, show,
     * describe or clarify, i.e. a request ("can I ask you to turn on the heater"). Every other wrapper form is checked against the old guard.
     */
    private static boolean isRequestAfterWrapper(String input) {
        return input.toLowerCase().matches("(?s).*"
            + "(?:(?:can|could|may|might)\\s+(?:i|we)\\s+ask|(?:am\\s+i|are\\s+we)\\s+able\\s+to\\s+ask|is\\s+it\\s+(?:ok|okay)\\s+(?:to|if\\s+(?:i|we))\\s+ask)"
            + "\\s+(?:you|us)\\s+to\\s+(?!explain|tell|show|describe|clarify).*");
    }

    // Nate (2026-11-01): "can/could/may" in "can I ask ..." is not part of the inquiry; "am I able to ask" standing alone is a question
    // answered rhetorically ("you can ask, and I will do my best"). What follows a wrapper is judged as it stands.

    @Test
    void aWrapperFollowedByAQuestionIsAQuestion() {
        for (String q : List.of("Can I ask how the heater works", "May I ask who can silence the siren", "Could I ask whether the valve is closed",
                "Can I ask you why it is cold", "Might I ask what Fire does on a workflow?", "Can we ask: where is the token kept",
                "Am I able to ask how to add a device", "Is it ok to ask who can see the cameras?")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isTrue();
        }
    }

    @Test
    void aWrapperStandingAloneIsAQuestionAnsweredRhetorically() {
        for (String q : List.of("Can I ask a question?", "Could I ask you something", "May I ask", "Am I able to ask?", "Are we able to ask a question",
                "Is it ok to ask something", "Can I ask you a quick question?", "Hi, could I ask something?")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isTrue();
        }
    }

    @Test
    void aWrapperFollowedByARequestIsTheRequestNotAQuestion() {
        for (String q : List.of("Can I ask you to turn on the heater?", "Could I ask you to silence the siren", "May I ask you to close the valve")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isFalse();
        }
    }

    /** One case per piece of the wrapper pattern: take a piece out and one of these (or the cases above) fails. */
    @Test
    void everyWrapperVariantFollowedByARequestIsARequest() {
        for (String q : List.of("Might I ask you to close the valve", "Am I able to ask you to turn on the heater", "Are we able to ask you to silence the siren",
                "Is it ok to ask you to turn on the heater", "Is it okay if we ask you to close the valve", "Can we ask you to close the valve",
                "Could we ask you to turn on the heater")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isFalse();
        }
    }

    @Test
    void theWrapperMustEndAtAWordBoundary() {
        // "asking" is not "ask": without the boundary the wrapper would match and the request after it would be let through
        assertThat(QuestionShape.isQuestion("Can I asking you to turn on the heater")).isTrue();
    }

    @Test
    void aWrapperFollowedByAnInquiryThatIsNotALeadWordIsAQuestion() {
        for (String q : List.of("Can I ask if the valve is closed", "Could I ask about the heater", "May I ask for help with the heater",
                "Can I ask a question about the heater", "Can I ask something about the heater", "Can I ask a quick question about the heater",
                "Can I ask you to explain how the heater works", "Can I ask you to tell me what Fire does", "Can I ask when the heater turns on",
                "Can I ask anything about the cameras")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isTrue();
        }
    }

    @Test
    void theAfterWrapperWordsDoNotMakeAnOrdinaryCommandAQuestion() {
        for (String q : List.of("If the door opens, turn on the heater", "For the cabin, turn on the heater", "About now, turn on the heater",
                "When the door opens, turn on the heater")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isFalse();
        }
    }

    @Test
    void aQuestionWordFollowedByAHyphenOrBracketIsNotMistakenForAListMarker() {
        // every form the old guard recognised must still be recognised (a form it missed is not a regression)
        for (String q : List.of("is-it on", "is - it on", "do) I need a token", "who) can silence the siren", "does-the heater need a plug",
                "how- do I turn it off", "what-is the heater doing", "whom- do I ask", "id) how does it work", "who] can silence the siren",
                "how> do I turn it off", "what}is the heater doing")) {
            if (QuestionShapePre377.isQuestion(q)) assertThat(QuestionShape.isQuestion(q)).as(q).isTrue();
        }
    }

    @Test
    void theHyphenAndBracketFormsTheReviewNamedAreRecognised() {
        for (String q : List.of("how- do I turn it off", "what-is the heater doing", "whom- do I ask", "id) how does it work")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isTrue();
        }
    }
}
