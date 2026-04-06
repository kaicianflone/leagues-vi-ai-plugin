package com.leaguesai.core.monitors;

import com.leaguesai.core.events.XpGainEvent;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;
import java.util.Map;

@Singleton
public class XpMonitor {

    private final Client client;
    private final EventBus eventBus;
    private final Map<Skill, Integer> previousXp = new EnumMap<>(Skill.class);

    @Inject
    public XpMonitor(Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
    }

    public void initialize() {
        for (Skill skill : Skill.values()) {
            previousXp.put(skill, client.getSkillExperience(skill));
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event) {
        Skill skill = event.getSkill();
        int currentXp = event.getXp();
        int previous = previousXp.getOrDefault(skill, 0);
        int delta = currentXp - previous;

        if (delta > 0) {
            eventBus.post(new XpGainEvent(skill, delta, currentXp));
            previousXp.put(skill, currentXp);
        }
    }
}
