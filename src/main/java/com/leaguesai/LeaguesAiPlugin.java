package com.leaguesai;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
    name = "Leagues AI",
    description = "AI-powered Leagues VI coach with goal planning, overlays, and chat",
    tags = {"leagues", "ai", "coach", "tasks", "overlay"}
)
public class LeaguesAiPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private LeaguesAiConfig config;

    @Override
    protected void startUp() throws Exception
    {
        log.info("Leagues AI plugin started");
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("Leagues AI plugin stopped");
    }

    @Provides
    LeaguesAiConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LeaguesAiConfig.class);
    }
}
