package com.leaguesai.data.model;

public enum Difficulty {
    EASY(1),
    MEDIUM(2),
    HARD(3),
    ELITE(4),
    MASTER(5);

    private final int tier;

    Difficulty(int tier) {
        this.tier = tier;
    }

    public int getTier() {
        return tier;
    }

    public static Difficulty fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Difficulty value cannot be null");
        }
        for (Difficulty d : values()) {
            if (d.name().equalsIgnoreCase(value.trim())) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown difficulty: " + value);
    }
}
