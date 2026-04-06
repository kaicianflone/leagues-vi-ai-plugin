package com.leaguesai.core.monitors;

import com.leaguesai.core.events.XpGainEvent;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class XpMonitorTest {

    @Mock
    private Client client;

    @Mock
    private EventBus eventBus;

    private XpMonitor xpMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        xpMonitor = new XpMonitor(client, eventBus);
    }

    @Test
    public void testDetectsXpGain() {
        // Seed all skills with 0 xp
        for (Skill skill : Skill.values()) {
            when(client.getSkillExperience(skill)).thenReturn(0);
        }
        xpMonitor.initialize();

        // Fire StatChanged with xp=100 for ATTACK
        StatChanged event = new StatChanged(Skill.ATTACK, 100, 1, 1);
        xpMonitor.onStatChanged(event);

        ArgumentCaptor<XpGainEvent> captor = ArgumentCaptor.forClass(XpGainEvent.class);
        verify(eventBus, times(1)).post(captor.capture());

        XpGainEvent fired = captor.getValue();
        assertEquals(Skill.ATTACK, fired.getSkill());
        assertEquals(100, fired.getDelta());
        assertEquals(100, fired.getTotalXp());
    }

    @Test
    public void testNoEventWhenNoXpChange() {
        // Seed ATTACK with 100 xp
        for (Skill skill : Skill.values()) {
            when(client.getSkillExperience(skill)).thenReturn(0);
        }
        when(client.getSkillExperience(Skill.ATTACK)).thenReturn(100);
        xpMonitor.initialize();

        // Fire StatChanged with same xp=100, delta should be 0
        StatChanged event = new StatChanged(Skill.ATTACK, 100, 1, 1);
        xpMonitor.onStatChanged(event);

        verify(eventBus, never()).post(any());
    }
}
