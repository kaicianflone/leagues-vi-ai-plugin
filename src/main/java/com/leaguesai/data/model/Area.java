package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Area {
    private String id;
    private String name;
    private int unlockCost;
    private List<String> unlockRequires;
    private List<Integer> regionIds;
}
