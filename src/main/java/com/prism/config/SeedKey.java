package com.prism.config;

import lombok.Data;
import java.util.List;

@Data
public class SeedKey {
    private String key;
    private String name;
    private String team;
    private List<String> modelAllowlist;
    private int rpmLimit;
    private double monthlyBudgetUsd;
}
