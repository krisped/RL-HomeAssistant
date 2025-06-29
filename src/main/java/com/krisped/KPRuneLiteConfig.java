package com.krisped;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("kp_runelite_ha")
public interface KPRuneLiteConfig extends Config
{
	@ConfigItem(
			keyName = "haUrl",
			name = "Home Assistant URL",
			description = "Eks: http://homeassistant.local:8123"
	)
	default String haUrl()
	{
		return "";
	}

	@ConfigItem(
			keyName = "haToken",
			name = "Home Assistant Access Token",
			description = "Ditt long-lived token fra Home Assistant"
	)
	default String haToken()
	{
		return "";
	}
}
