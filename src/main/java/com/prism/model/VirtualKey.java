package com.prism.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("virtual_keys")
public class VirtualKey {
    @Id
    private Long id;

    @Column("key_hash")
    private String keyHash;

    private String name;
    private String team;

    @Column("model_allowlist")
    private String modelAllowlist; // stored as JSON string

    @Column("rpm_limit")
    private int rpmLimit;

    @Column("monthly_budget_micro_usd")
    private long monthlyBudgetMicroUsd;

    private String status;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    public List<String> getAllowedModels() {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(modelAllowlist,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    public boolean isActive() {
        return "active".equals(status);
    }
}