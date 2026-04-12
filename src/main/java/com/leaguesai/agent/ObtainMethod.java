package com.leaguesai.agent;

/**
 * How a given item can be obtained. Drives BFS recursion in
 * {@link ItemDependencyGraph}: recursable methods indicate that the
 * {@code source_id} is itself an ingredient item that must also be resolved;
 * terminal methods indicate the BFS should stop at that node.
 */
public enum ObtainMethod {

    /** Smithing skill recipe; source_id is an ingredient item_id, BFS recurses. */
    SMITHED,

    /** Crafting skill recipe; source_id is an ingredient item_id, BFS recurses. */
    CRAFTED,

    /** Fletching skill recipe; source_id is an ingredient item_id, BFS recurses. */
    FLETCHED,

    /** Herblore skill recipe; source_id is an ingredient item_id, BFS recurses. */
    HERBLORE,

    /** Cooking skill recipe; source_id is an ingredient item_id, BFS recurses. */
    COOKED,

    /** Monster drop; source_id is a monster name, terminal node. */
    DROPPED,

    /** Quest reward; terminal node. */
    QUEST_REWARD,

    /** Shop purchase; terminal node. */
    SHOP,

    /** Skill milestone reward; terminal node. */
    SKILL_REWARD;

    /**
     * Returns {@code true} for recipe-based methods where the {@code source_id}
     * is an ingredient item that the BFS should also expand. Returns {@code false}
     * for terminal methods (drops, quest rewards, shop, skill reward).
     */
    public boolean isRecursable() {
        switch (this) {
            case SMITHED:
            case CRAFTED:
            case FLETCHED:
            case HERBLORE:
            case COOKED:
                return true;
            default:
                return false;
        }
    }
}
