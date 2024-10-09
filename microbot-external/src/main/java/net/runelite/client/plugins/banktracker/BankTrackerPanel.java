package net.runelite.client.plugins.banktracker;

import net.runelite.api.Varbits;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Item;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

public class BankTrackerPanel extends PluginPanel {
    private static final int MAX_BANK_TABS = 9;

    private final BankTrackerBox favoritesPanel;
    private final BankTrackerBox unsortedPanel;
    private final List<BankTrackerBox> tabPanels = new ArrayList<>(MAX_BANK_TABS);
    private final int[] tabCounts = new int[MAX_BANK_TABS];
    private List<BankTrackerItem> items;
    private Function<Rs2Item, BankTrackerItem> bankItemFactory;

    public BankTrackerPanel(ItemManager itemManager, Function<Rs2Item, BankTrackerItem> bankItemFactory) {
        this.bankItemFactory = bankItemFactory;

		setBorder(new EmptyBorder(6, 6, 6, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        items = getItems();
        updateTabCounts();
        favoritesPanel = new BankTrackerBox(itemManager, "Favorites",
            () -> items.stream().filter(BankTrackerItem::isFavorite).collect(Collectors.toList()));
        for (int i=0; i < MAX_BANK_TABS; i++) {
            tabPanels.add(new BankTrackerBox(itemManager, "Tab "+(i+1), supplierForTab(i)));
        }
        unsortedPanel = new BankTrackerBox(itemManager, "Unsorted", supplierForTab(9));
        add(favoritesPanel);
        for (var tab : tabPanels) add(tab);
        add(unsortedPanel);

    }

    public void updateItems() {
        items = getItems();
        updateTabCounts();
        Microbot.getClientThread().runOnClientThread(() -> {
            favoritesPanel.rebuild();
            for (var tab : tabPanels) tab.rebuild();
            unsortedPanel.rebuild();
            return true;
        });
    }

    private List<BankTrackerItem> getItems() {
        return Rs2Bank.bankItems().stream().map(bankItemFactory).collect(Collectors.toList());
    }

    private Supplier<List<BankTrackerItem>> supplierForTab(int tab) {
        final int start = IntStream.range(0, tab).map(x -> tabCounts[x]).sum();
        final int length = tab == MAX_BANK_TABS ? items.size()-start-1 : tabCounts[tab];
        return () -> IntStream.range(start, start+length)
                              .mapToObj(items::get)
                              .collect(Collectors.toList());

    }

    private void updateTabCounts() {
        tabCounts[0] = Microbot.getVarbitValue(Varbits.BANK_TAB_ONE_COUNT);
        tabCounts[1] = Microbot.getVarbitValue(Varbits.BANK_TAB_TWO_COUNT);
        tabCounts[2] = Microbot.getVarbitValue(Varbits.BANK_TAB_THREE_COUNT);
        tabCounts[3] = Microbot.getVarbitValue(Varbits.BANK_TAB_FOUR_COUNT);
        tabCounts[4] = Microbot.getVarbitValue(Varbits.BANK_TAB_FIVE_COUNT);
        tabCounts[5] = Microbot.getVarbitValue(Varbits.BANK_TAB_SIX_COUNT);
        tabCounts[6] = Microbot.getVarbitValue(Varbits.BANK_TAB_SEVEN_COUNT);
        tabCounts[7] = Microbot.getVarbitValue(Varbits.BANK_TAB_EIGHT_COUNT);
        tabCounts[8] = Microbot.getVarbitValue(Varbits.BANK_TAB_NINE_COUNT);
    }
}
