package com.leaguesai.data.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A Demonic Pacts League pact (Leagues VI). Pacts provide combat modifiers
 * and are classified by {@code nodeType}: {@code minor}, {@code major}, or
 * {@code master}.
 *
 * <p>Phase 1: pacts are stored as a flat list. The wiki does not yet document
 * the unlock tree / skill tree progression, so {@link #parentId} and
 * {@link #unlockRequires} are reserved for phase 2 (launch day 2026-04-15) and
 * will be {@code null} until then. See CLAUDE.md "Phase 2 TODO" section.
 */
@Data
@Builder
public class Pact {
    private String id;
    private String name;
    /** One of {@code minor}, {@code major}, {@code master}. */
    private String nodeType;
    /** Verbatim effect text from the wiki — do not paraphrase downstream. */
    private String effect;
    private String wikiUrl;

    /** Reserved for phase 2 — parent pact in the unlock tree. {@code null} in phase 1. */
    private String parentId;
    /** Reserved for phase 2 — list of pact IDs that must be unlocked first. {@code null} in phase 1. */
    private List<String> unlockRequires;
}
