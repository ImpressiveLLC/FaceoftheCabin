package com.cabin.orchestrator.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** W-140: what is left of a request once its politeness wrapper is taken off. */
class QuestionShapeUnwrapTest {

    private static void unwraps(String text, String remainder) {
        QuestionShape.Unwrapped u = QuestionShape.unwrap(text);
        assertThat(u.wrapped()).as(text).isTrue();
        assertThat(u.remainder()).as(text).isEqualTo(remainder);
    }

    @Test
    void whatFollowsAWrapperIsTheRequestEvenAFragment() {
        unwraps("Can I ask weather", "weather");
        unwraps("can i ask weather?", "weather?");
        unwraps("may I inquire about the heater", "about the heater");
        unwraps("Could we enquire how the valve works", "how the valve works");
    }

    @Test
    void forYouToIsDroppedSoAWrappedCommandReadsAsTheCommand() {
        unwraps("Can I ask for you to turn on the heater", "turn on the heater");
        unwraps("Could I ask you to turn on the heater", "turn on the heater");
        unwraps("May I ask for weather", "weather");
        unwraps("Hi, can I ask for you to close the valve", "close the valve");
        unwraps("Please could I ask you to silence the siren", "silence the siren");
    }

    @Test
    void everyWrapperVariantIsRecognised() {
        for (String wrapper : new String[] {"Can I ask", "Could I ask", "May I ask", "Might I ask", "Can we ask", "Am I able to ask", "Are we able to ask",
                "Is it ok to ask", "Is it okay if I ask", "Is it ok if we ask", "May I inquire", "Can I enquire"}) {
            unwraps(wrapper + " weather", "weather");
        }
    }

    @Test
    void aWrapperWithNothingAfterItHasABlankRemainder() {
        for (String text : new String[] {"may I inquire something", "Can I ask a question?", "Could I ask you something", "Am I able to ask?", "Can I ask one quick question",
                "Can I ask anything."}) {
            unwraps(text, "");
        }
    }

    @Test
    void textWithoutAWrapperIsLeftAlone() {
        for (String text : new String[] {"turn on the heater", "How do I turn it off?", "Can you ask the heater to turn on", "I can ask weather", "Can I turn on the heater", ""}) {
            QuestionShape.Unwrapped u = QuestionShape.unwrap(text);
            assertThat(u.wrapped()).as(text).isFalse();
            assertThat(u.remainder()).as(text).isEqualTo(text);
        }
    }

    @Test
    void theWrapperMustEndAtAWordBoundary() {
        assertThat(QuestionShape.unwrap("Can I asking you to turn on the heater").wrapped()).isFalse();
    }

    @Test
    void forYouToIsARequestButForHelpIsAnInquiry() {
        assertThat(QuestionShape.isQuestion("Can I ask for you to turn on the heater")).isFalse();
        assertThat(QuestionShape.isQuestion("Can I ask for us to turn on the heater")).isFalse();
        assertThat(QuestionShape.isQuestion("May I ask for help with the heater")).isTrue();
        assertThat(QuestionShape.isQuestion("Can I ask for the heater status")).isTrue();
    }

    @Test
    void forYouOrUsToFollowedByAnInquiryVerbIsStillAnInquiry() {
        for (String q : List.of("can I ask for you to explain how the heater works", "can I ask for you to tell me if the heater is on",
                "can I ask for us to tell me if the heater is on", "Can I ask for you to describe the heater", "Can I ask for you to show me the heater",
                "Can I ask for you to clarify the heater")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isTrue();
        }
        for (String q : List.of("Can I ask for you to turn on the heater", "Can I ask for us to turn on the heater",
                "Can I ask for you to turn on the heater if it is cold")) {
            assertThat(QuestionShape.isQuestion(q)).as(q).isFalse();
        }
    }

    @Test
    void theFillerTakesUsAndTrailingPunctuation() {
        unwraps("Can I ask for us to turn on the heater", "turn on the heater");
        unwraps("Can I ask for you?", "");
        unwraps("Can I ask for us.", "");
    }

    @Test
    void aWrapperThenJustYouHasNothingAfterIt() {
        unwraps("Can I ask for you", "");
        unwraps("Can I ask you", "");
    }

    @Test
    void aVeryLongRunOfGreetingsDoesNotOverflowTheStack() {
        String text = "hi ".repeat(10_000) + "can I ask weather";
        assertThat(QuestionShape.unwrap(text).wrapped()).isFalse();
        assertThat(QuestionShape.isQuestion(text)).isIn(true, false);
    }
}
