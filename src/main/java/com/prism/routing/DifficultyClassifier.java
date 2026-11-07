package com.prism.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DifficultyClassifier {

    private static final Set<String> HARD_KEYWORDS = Set.of(
            "analyze", "compare", "contrast", "evaluate", "synthesize",
            "design", "architect", "implement", "debug", "optimize",
            "prove", "derive", "calculate", "solve", "algorithm",
            "trade-off", "tradeoff", "pros and cons", "explain why",
            "step by step", "reasoning", "multi-step", "complex",
            "security", "vulnerability", "code review",
            "mathematical", "equation", "theorem", "proof",
            "distributed", "concurrent", "race condition",
            "refactor", "migration", "scale"
    );

    private static final Set<String> EASY_KEYWORDS = Set.of(
            "what is", "define", "who is", "when was", "where is",
            "translate", "convert", "list", "name", "count",
            "hello", "hi", "thanks", "thank you",
            "yes or no", "true or false",
            "capital of", "color of", "meaning of"
    );

    private static final Pattern CODE_PATTERN =
            Pattern.compile("```|def |function |class |import |public |private ");

    private static final Pattern MULTI_QUESTION =
            Pattern.compile("\\?.*\\?", Pattern.DOTALL);

    public String classify(List<com.prism.dto.ChatCompletionRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "easy";
        }

        String lastMessage = messages.get(messages.size() - 1).getContent();
        if (lastMessage == null || lastMessage.isBlank()) {
            return "easy";
        }

        String lower = lastMessage.toLowerCase();
        int score = 0;

        for (String keyword : HARD_KEYWORDS) {
            if (lower.contains(keyword)) {
                score += 3;
            }
        }

        for (String keyword : EASY_KEYWORDS) {
            if (lower.contains(keyword)) {
                score -= 2;
            }
        }

        if (CODE_PATTERN.matcher(lastMessage).find()) {
            score += 4;
        }

        if (MULTI_QUESTION.matcher(lastMessage).find()) {
            score += 2;
        }

        if (messages.size() > 1) {
            String systemPrompt = messages.stream()
                    .filter(m -> "system".equals(m.getRole()))
                    .map(m -> m.getContent())
                    .findFirst().orElse("");
            if (systemPrompt.length() > 200) {
                score += 2;
            }
        }

        long userMessages = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .count();
        if (userMessages > 3) {
            score += 2;
        }

        long sentenceCount = lastMessage.chars()
                .filter(c -> c == '.' || c == '?' || c == '!').count();
        if (sentenceCount > 3) {
            score += 1;
        }

        log.debug("Difficulty score for prompt: {} -> {}",
                lower.substring(0, Math.min(50, lower.length())), score);

        return score >= 3 ? "hard" : "easy";
    }

    public String classifyByLength(List<com.prism.dto.ChatCompletionRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) return "easy";
        String last = messages.get(messages.size() - 1).getContent();
        if (last == null) return "easy";
        return last.length() > 200 ? "hard" : "easy";
    }
}
