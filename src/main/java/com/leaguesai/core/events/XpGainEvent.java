package com.leaguesai.core.events;

import lombok.Value;
import net.runelite.api.Skill;

@Value
public class XpGainEvent {
    Skill skill;
    int delta;
    int totalXp;
}
