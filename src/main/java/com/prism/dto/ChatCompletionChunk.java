package com.prism.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionChunk {

    @Builder.Default
    private String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

    @Builder.Default
    private String object = "chat.completion.chunk";

    @Builder.Default
    private long created = System.currentTimeMillis() / 1000;

    private String model;
    private List<ChunkChoice> choices;
    private ChatCompletionResponse.Usage usage; // only in final chunk

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkChoice {
        private int index;
        private Delta delta;

        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Delta {
        private String role;
        private String content;
    }
}