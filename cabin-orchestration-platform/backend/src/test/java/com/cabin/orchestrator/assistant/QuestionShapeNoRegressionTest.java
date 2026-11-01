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
        // "ask"-style capability questions
        "can I ask you to turn on the heater?", "could I ask whether the valve is closed", "may I ask who can silence the siren",
        "can i ask you something about the heater", "could i ask what the heater does");

    @Test
    void everyFormTheOldGuardRecognisedIsStillRecognised() {
        List<String> lost = new ArrayList<>();
        for (String prefix : PREFIXES) {
            for (String core : CORES) {
                String input = prefix + core;
                if (isAskWrapperForm(input)) continue; // Nate's rule 2026-11-01: judged by what follows the wrapper, see the tests below
                if (QuestionShapePre377.isQuestion(input) && !QuestionShape.isQuestion(input)) lost.add(input);
            }
        }
        assertThat(lost).as("forms recognised before #377 and not now").isEmpty();
    }

    /** can/could/may/might + I/we + ask, or "am I able to ask": the wrapper forms Nate's rule treats as not part of the inquiry. */
    private static boolean isAskWrapperForm(String input) {
        return input.toLowerCase().matches("(?s).*" + "(?:(?:can|could|may|might)\\s+(?:i|we)\\s+ask|(?:am\\s+i|are\\s+we)\\s+able\\s+to\\s+ask|is\\s+it\\s+(?:ok|okay)\\s+(?:to|if\\s+(?:i|we))\\s+ask)" + ".*");
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

    @Test
    void aQuestionWordFollowedByAHyphenOrBracketIsNotMistakenForAListMarker() {
        for (String q : List.of("is-it on", "is - it on", "do) I need a token", "who) can silence the siren", "does-the heater need a plug")) {
            assertThat(QuestionShape.isQuestion(q) || !QuestionShapePre377.isQuestion(q)).as(q).isTrue();
        }
    }
}
