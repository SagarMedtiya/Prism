package com.prism.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DtoShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void chatRequestDeserializes() throws Exception {
        String json = """
                {
                    "model": "fast",
                    "messages": [
                        {"role": "user", "content": "Hi"}
                    ],
                    "stream": false,
                    "temperature": 0.0,
                    "max_tokens": 100
                }
                """;
        ChatCompletionRequest req = mapper.readValue(json, ChatCompletionRequest.class);
        assertEquals("fast", req.getModel());
        assertEquals(1, req.getMessages().size());
        assertEquals("user", req.getMessages().get(0).getRole());
        assertFalse(req.isStream());
        assertEquals(0.0, req.getTemperature());
        assertEquals(100, req.getMaxTokens());
    }

    @Test
    void chatResponseSerializesOpenAIShape() throws Exception {
        ChatCompletionResponse resp = ChatCompletionResponse.builder()
                .model("mock-fast-v1")
                .choices(List.of(ChatCompletionResponse.Choice.builder()
                        .index(0)
                        .message(ChatCompletionResponse.Message.builder()
                                .role("assistant")
                                .content("Hello!")
                                .build())
                        .build()))
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(10)
                        .completionTokens(5)
                        .totalTokens(15)
                        .build())
                .build();

        String json = mapper.writeValueAsString(resp);

        assertTrue(json.contains("\"model\""));
        assertTrue(json.contains("\"choices\""));
        assertTrue(json.contains("\"usage\""));
        assertTrue(json.contains("\"prompt_tokens\""));
        assertTrue(json.contains("\"completion_tokens\""));
        assertTrue(json.contains("\"total_tokens\""));
        assertTrue(json.contains("\"finish_reason\""));
        assertTrue(json.contains("\"id\""));
        assertTrue(json.contains("chatcmpl-"));
    }

    @Test
    void errorResponseShape() throws Exception {
        ErrorResponse err = ErrorResponse.of("rate_limited", "Too many requests");
        String json = mapper.writeValueAsString(err);

        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("\"code\":\"rate_limited\""));
        assertTrue(json.contains("\"message\":\"Too many requests\""));
    }

    @Test
    void chatChunkSerializesCorrectly() throws Exception {
        ChatCompletionChunk chunk = ChatCompletionChunk.builder()
                .model("mock-fast-v1")
                .choices(List.of(ChatCompletionChunk.ChunkChoice.builder()
                        .index(0)
                        .delta(ChatCompletionChunk.Delta.builder()
                                .content("Hello ")
                                .build())
                        .build()))
                .build();

        String json = mapper.writeValueAsString(chunk);
        assertTrue(json.contains("\"delta\""));
        assertTrue(json.contains("\"Hello \""));
        assertTrue(json.contains("chat.completion.chunk"));
    }

    @Test
    void requestStreamDefaultsFalse() {
        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model("fast")
                .messages(List.of())
                .build();
        assertFalse(req.isStream());
    }

    @Test
    void requestTemperatureDefaultsToOne() {
        ChatCompletionRequest req = ChatCompletionRequest.builder()
                .model("fast")
                .messages(List.of())
                .build();
        assertEquals(1.0, req.getTemperature());
    }
}
