package com.prism.routing;

import com.prism.dto.ChatCompletionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DifficultyClassifierTest {

    private final DifficultyClassifier classifier = new DifficultyClassifier();

    private List<ChatCompletionRequest.Message> msg(String content) {
        return List.of(ChatCompletionRequest.Message.builder()
                .role("user").content(content).build());
    }

    @Test
    void simpleQuestionIsEasy() {
        assertEquals("easy", classifier.classify(msg("What is the capital of France?")));
    }

    @Test
    void complexQuestionIsHard() {
        assertEquals("hard", classifier.classify(
                msg("Design a distributed cache with trade-offs between consistency models")));
    }

    @Test
    void shortButHardIsHard() {
        assertEquals("hard", classifier.classify(msg("Prove P != NP.")));
    }

    @Test
    void longButTrivialIsEasy() {
        String longEasy = "Here is a long list of items: apple, banana, cherry, date, " +
                "elderberry, fig, grape, honeydew, kiwi, lemon, mango, nectarine, " +
                "orange, papaya, quince, raspberry, strawberry, tangerine, ugli fruit, " +
                "vanilla bean, watermelon, xigua, yam, zucchini. " +
                "What is the first item listed?";
        assertEquals("easy", classifier.classify(msg(longEasy)));
    }

    @ParameterizedTest
    @CsvSource({
        "'Hello', easy",
        "'What is 2+2?', easy",
        "'Analyze this algorithm for complexity', hard",
        "'Debug this race condition in concurrent code', hard"
    })
    void classifiesCorrectly(String prompt, String expected) {
        assertEquals(expected, classifier.classify(msg(prompt)));
    }

    @Test
    void beatsLengthBaseline() {
        String shortHard = "Derive the backpropagation algorithm.";
        assertEquals("easy", classifier.classifyByLength(msg(shortHard)));
        assertEquals("hard", classifier.classify(msg(shortHard)));

        String longEasy = "I have a really long question here about something very simple. " +
                "It goes on and on with lots of detail but ultimately just asks: " +
                "What color is the sky? Please tell me. " +
                "I really want to know. It's for a school project. " +
                "My teacher wants me to find out. What is it?";
        assertEquals("hard", classifier.classifyByLength(msg(longEasy)));
        assertEquals("easy", classifier.classify(msg(longEasy)));
    }
}
