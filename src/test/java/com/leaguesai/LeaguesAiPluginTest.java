package com.leaguesai;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Real unit test for {@link LeaguesAiPlugin}.
 *
 * <p>We cannot launch a full RuneLite client in unit tests, so this test
 * verifies plugin metadata, the @Provides binding, and that the required
 * injected fields exist (catches accidental field renames or removals).
 */
public class LeaguesAiPluginTest {

    @Test
    public void testPluginDescriptorPresent() {
        PluginDescriptor descriptor = LeaguesAiPlugin.class.getAnnotation(PluginDescriptor.class);
        assertNotNull("Plugin must have @PluginDescriptor", descriptor);
        assertEquals("Leagues AI", descriptor.name());
        assertTrue("description should mention Leagues",
                descriptor.description().toLowerCase().contains("leagues"));
        assertTrue("at least one tag should be declared", descriptor.tags().length > 0);
    }

    @Test
    public void testProvideConfig() throws Exception {
        LeaguesAiPlugin plugin = new LeaguesAiPlugin();
        ConfigManager configManager = mock(ConfigManager.class);
        LeaguesAiConfig mockConfig = mock(LeaguesAiConfig.class);
        when(configManager.getConfig(LeaguesAiConfig.class)).thenReturn(mockConfig);

        Method method = LeaguesAiPlugin.class.getDeclaredMethod("provideConfig", ConfigManager.class);
        method.setAccessible(true);
        Object result = method.invoke(plugin, configManager);

        assertSame(mockConfig, result);
        verify(configManager).getConfig(LeaguesAiConfig.class);
    }

    @Test
    public void testRequiredFieldsPresent() {
        String[] requiredFields = {
            "client", "config", "clientToolbar", "overlayManager", "eventBus",
            "xpMonitor", "inventoryMonitor", "locationMonitor",
            "tileOverlay", "arrowOverlay", "npcOverlay", "objectOverlay",
            "groundItemOverlay", "minimapOverlay", "pathOverlay", "widgetOverlay",
            "overlayController", "contextAssembler"
        };
        for (String name : requiredFields) {
            try {
                LeaguesAiPlugin.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                fail("Required field missing: " + name);
            }
        }
    }
}
