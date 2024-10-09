package net.runelite.client.plugins.banktracker;

import net.runelite.api.InventoryID;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Item;
import net.runelite.client.plugins.utils.KepowUtils;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.*;

import com.google.inject.Provides;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Timer;
import java.util.TimerTask;


@PluginDescriptor(
        name = KepowUtils.Kepow + "Bank Tracker",
        description = "Bank Tracker for Microbot",
        tags = {"microbot", "bank"},
        hidden = true,
        alwaysOn = true
)
public class BankTrackerPlugin extends Plugin {
    private NavigationButton navButton;
    public static BankTrackerPanel panel;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private BankTrackerConfig config;

    @Inject
    private ItemManager itemManager;

    private boolean refreshItems = false;
    private Timer timer;
	private List<String> favoriteItems = new ArrayList<>();

    @Provides
    BankTrackerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BankTrackerConfig.class);
    }

    @Override
    protected void startUp() {
        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "bank.png");
        favoriteItems = Text.fromCSV(config.getFavoriteItems());
        panel = new BankTrackerPanel(itemManager, this::buildBankItem);
        navButton = NavigationButton.builder()
                .tooltip("Bank Tracker")
                .icon(icon)
                .priority(1)
                .panel(panel)
                .build();
        clientToolbar.addNavigation(navButton);

        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (refreshItems) {
                    SwingUtilities.invokeLater(panel::updateItems);
                    refreshItems = false;
                }
            }
        }, 1800, 1800);
        refreshItems = true;

    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() == InventoryID.BANK.getId()) {
            refreshItems = true;
        }
    }

    @Override
    protected void shutDown() {
        clientToolbar.removeNavigation(navButton);
        timer.cancel();
    }

	// @Subscribe
	// public void onConfigChanged(ConfigChanged event)
	// {
	// 	if (event.getGroup().equals(BankTrackerConfig.GROUP))
	// 	{
	// 		if ("favoriteItems".equals(event.getKey()))
	// 		{
	// 			favoriteItems = Text.fromCSV(config.getFavoriteItems());
	// 			SwingUtilities.invokeLater(panel::updateItems);
	// 		}
	// 	}
	// }

    public BankTrackerItem buildBankItem(Rs2Item item) {
        return new BankTrackerItem(item, favoriteItems.contains(item.getName()), this::toggleItem);
    }

	void toggleItem(BankTrackerItem item)
	{
		final Set<String> favoritesSet = new LinkedHashSet<>(favoriteItems);

		if (item.isFavorite())
		{
			favoritesSet.add(item.getItem().getName());
		}
		else
		{
			favoritesSet.remove(item.getItem().getName());
		}

		config.setFavoriteItems(Text.toCSV(favoritesSet));
        favoriteItems = List.copyOf(favoritesSet);
        SwingUtilities.invokeLater(panel::updateItems);
		// the config changed will update the panel
	}

}
