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
                if (QuestionShapePre377.isQuestion(input) && !QuestionShape.isQuestion(input)) lost.add(input);
            }
        }
        assertThat(lost).as("forms recognised before #377 and not now").isEmpty();
    }

    @Test
    void aCapabilityQuestionStartingWithAnAskOpenerIsStillAQuestion() {
        for (String q : List.of("Can I ask you to turn on the heater?", "Could I ask whether the valve is closed", "May I ask who can silence the siren",
                "can i ask you something about the heater", "Can I ask how the heater works")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isTrue();
        }
    }

    @Test
    void aQuestionWordFollowedByAHyphenOrBracketIsNotMistakenForAListMarker() {
        for (String q : List.of("is-it on", "is - it on", "do) I need a token", "who) can silence the siren", "does-the heater need a plug")) {
            assertThat(QuestionShape.isQuestion(q) || !QuestionShapePre377.isQuestion(q)).as(q).isTrue();
        }
    }
}
