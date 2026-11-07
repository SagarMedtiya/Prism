package com.prism.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("cache_entries")
public class CacheEntry {
    @Id
    private Long id;

    @Column("key_id")
    private Long keyId;

    @Column("prompt_hash")
    private String promptHash;

    private byte[] embedding;

    private String model;

    @Column("response_body")
    private String responseBody;

    @Column("prompt_tokens")
    private int promptTokens;

    @Column("completion_tokens")
    private int completionTokens;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("expires_at")
    private LocalDateTime expiresAt;
}