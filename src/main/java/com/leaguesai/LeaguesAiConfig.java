package com.leaguesai;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

import java.awt.Color;

@ConfigGroup("leaguesai")
public interface LeaguesAiConfig extends Config
{
    @ConfigSection(
        name = "API Settings",
        description = "Settings for the OpenAI API connection",
        position = 0
    )
    String apiSection = "apiSettings";

    @ConfigSection(
        name = "Display Settings",
        description = "Settings for overlays and visual display",
        position = 1
    )
    String displaySection = "displaySettings";

    @ConfigItem(
        keyName = "openaiApiKey",
        name = "OpenAI API Key",
        description = "Your OpenAI API key",
        secret = true,
        section = "apiSettings",
        position = 0
    )
    default String openaiApiKey()
    {
        return "";
    }

    @ConfigItem(
        keyName = "openaiModel",
        name = "OpenAI Model",
        description = "The OpenAI model to use for AI coaching",
        section = "apiSettings",
        position = 1
    )
    default String openaiModel()
    {
        return "gpt-4o";
    }

    @ConfigItem(
        keyName = "autoMode",
        name = "Auto Mode",
        description = "Enable automatic AI coaching suggestions",
        section = "apiSettings",
        position = 2
    )
    default boolean autoMode()
    {
        return true;
    }

    @ConfigItem(
        keyName = "currentGoal",
        name = "Current Goal",
        description = "The current goal being pursued in Leagues VI",
        section = "apiSettings",
        position = 3
    )
    default String currentGoal()
    {
        return "";
    }

    @ConfigItem(
        keyName = "overlayColor",
        name = "Overlay Color",
        description = "Color used for AI coaching overlays",
        section = "displaySettings",
        position = 0
    )
    default Color overlayColor()
    {
        return Color.CYAN;
    }

    @ConfigItem(
        keyName = "animationSpeed",
        name = "Animation Speed",
        description = "Speed of overlay animations in milliseconds",
        section = "displaySettings",
        position = 1
    )
    default int animationSpeed()
    {
        return 200;
    }
}
