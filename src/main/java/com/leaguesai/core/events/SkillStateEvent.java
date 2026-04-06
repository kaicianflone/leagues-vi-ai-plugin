package com.leaguesai.core.events;

import lombok.Value;
import net.runelite.api.Skill;

import java.util.Map;

@Value
public class SkillStateEvent {
    Map<Skill, Integer> levels;
    Map<Skill, Integer> boostedLevels;
}
