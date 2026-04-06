package com.leaguesai;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Developer convenience entry point for running the plugin inside a full
 * RuneLite client. This is NOT a unit test — it has no @Test methods and is
 * never invoked by CI. Run it from your IDE while iterating on the plugin.
 *
 * <p>The actual unit test for the plugin lives in {@link LeaguesAiPluginTest}.
 */
public class LeaguesAiDevLauncher
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(LeaguesAiPlugin.class);
        RuneLite.main(args);
    }
}
