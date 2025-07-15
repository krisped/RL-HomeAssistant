package com.krisped;

import net.runelite.client.config.*;

/**
 * KP Home Assistant Plugin Configuration
 */
@ConfigGroup("kp_home_assistant")
public interface HomeAssistantConfig extends Config
{
	/* ─────────────────────────── Home-Assistant API ─────────────────────────── */
	@ConfigSection(
			name             = "Home Assistant Config",
			description      = "Enter your Home Assistant URL and token",
			position         = 0,
			closedByDefault  = true
	)
	String sectionApi = "api";

	@ConfigItem(
			keyName     = "haUrl",
			name        = "Home Assistant URL",
			description = "e.g. http://homeassistant.local:8123",
			position    = 1,
			section     = sectionApi
	)
	default String haUrl() { return ""; }

	@ConfigItem(
			keyName     = "haToken",
			name        = "Access Token",
			description = "Your long-lived token from Home Assistant",
			position    = 2,
			section     = sectionApi
	)
	default String haToken() { return ""; }


	/* ───────────────────────────── Status-toggles ───────────────────────────── */
	@ConfigSection(
			name             = "Status",
			description      = "Which statuses to send to Home Assistant",
			position         = 10,
			closedByDefault  = false
	)
	String sectionStatus = "status";

	@ConfigItem(
			keyName     = "showHealth",
			name        = "Show Health Status",
			description = "Enable sending current/max HP",
			position    = 11,
			section     = sectionStatus
	)
	default boolean showHealth() { return false; }

	@ConfigItem(
			keyName     = "showPrayer",
			name        = "Show Prayer Status",
			description = "Enable sending current/max Prayer",
			position    = 12,
			section     = sectionStatus
	)
	default boolean showPrayer() { return false; }

	@ConfigItem(
			keyName     = "showEnergy",
			name        = "Show Energy Status",
			description = "Enable sending current/max Energy",
			position    = 13,
			section     = sectionStatus
	)
	default boolean showEnergy() { return false; }

	@ConfigItem(
			keyName     = "showCurrentWorld",
			name        = "Show Current World",
			description = "Enable sending current world",
			position    = 14,
			section     = sectionStatus
	)
	default boolean showCurrentWorld() { return false; }

	@ConfigItem(
			keyName     = "showCurrentOpponent",
			name        = "Show Current Opponent",
			description = "Enable sending name of your combat target",
			position    = 15,
			section     = sectionStatus
	)
	default boolean showCurrentOpponent() { return false; }

	@ConfigItem(
			keyName     = "showCurrentLocation",
			name        = "Show Current Location",
			description = "Enable sending your in-game location",
			position    = 16,
			section     = sectionStatus
	)
	default boolean showCurrentLocation() { return false; }

	@ConfigItem(
			keyName     = "showSpecialAttack",
			name        = "Show Special Attack",
			description = "Enable sending special-attack energy",
			position    = 17,
			section     = sectionStatus
	)
	default boolean showSpecialAttack() { return false; }

	@ConfigItem(
			keyName     = "showCurrentSkill",
			name        = "Show Current Skill",
			description = "Enable sending the skill you are training",
			position    = 18,
			section     = sectionStatus
	)
	default boolean showCurrentSkill() { return false; }

	@ConfigItem(
			keyName     = "showIdleStatus",
			name        = "Show Idle Status",
			description = "Enable sending Idle/Active + idle seconds",
			position    = 19,
			section     = sectionStatus
	)
	default boolean showIdleStatus() { return false; }

	@ConfigItem(
			keyName     = "idleThresholdSeconds",
			name        = "Idle delay (s)",
			description = "Seconds without animation before you are considered Idle",
			position    = 20,
			section     = sectionStatus
	)
	@Range(min = 1, max = 60)
	default int idleThresholdSeconds() { return 5; }


	/* ───────────────────────────── Keybinding ─────────────────────────────── */
	@ConfigSection(
			name             = "Keyboard Server",
			description      = "Settings for keyboard HTTP listener",
			position         = 30,
			closedByDefault  = true
	)
	String sectionKeyboard = "keyboard";

	@ConfigItem(
			keyName     = "keyboardBindHost",
			name        = "Bind address",
			description = "Adresse å binde keyboard-serveren på (0.0.0.0 for alle nettverk)",
			position    = 31,
			section     = sectionKeyboard
	)
	default String keyboardBindHost()
	{
		return "0.0.0.0";
	}

	@ConfigItem(
			keyName     = "keyboardPort",
			name        = "Port",
			description = "Port for keyboard HTTP-serveren",
			position    = 32,
			section     = sectionKeyboard
	)
	@Range(min = 1024, max = 65535)
	default int keyboardPort()
	{
		return 8124;
	}
}
