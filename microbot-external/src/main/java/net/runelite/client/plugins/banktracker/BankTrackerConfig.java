package net.runelite.client.plugins.banktracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(BankTrackerConfig.GROUP)
public interface BankTrackerConfig extends Config
{
	String GROUP = "banktracker";
	@ConfigItem(
		keyName = "favoriteItems",
		name = "favorite items",
		description = "Configures which items should be shown in the favorites panel."
	)
	default String getFavoriteItems()
	{
		return "";
	}

	@ConfigItem(
		keyName = "favoriteItems",
		name = "",
		description = ""
	)
	void setFavoriteItems(String key);
}
