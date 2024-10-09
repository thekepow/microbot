package net.runelite.client.plugins.banktracker;

import java.util.function.Consumer;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Item;

@RequiredArgsConstructor
@AllArgsConstructor
public class BankTrackerItem {
    @Getter
    private final Rs2Item item;

    @Getter
    private boolean isFavorite;

    private final Consumer<BankTrackerItem> onFavoriteToggle;

    public void toggleFavorite() {
        isFavorite = !isFavorite;
        onFavoriteToggle.accept(this);
    }
}
