package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class Relic {
    private String id;
    private String name;
    private String description;
    private int tier;
    private int unlockCost;
    private Map<String, Object> effects;
}
