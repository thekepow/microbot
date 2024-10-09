package net.runelite.client.plugins.banktracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.Border;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Item;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

class BankTrackerBox extends JPanel
{
	private static final int ITEMS_PER_ROW = 5;
	private static final int TITLE_PADDING = 5;

	private final JPanel itemContainer = new JPanel();
	private final JPanel logTitle = new JPanel();
	private final ItemManager itemManager;

	private final Supplier<List<BankTrackerItem>> itemsSupplier;

	BankTrackerBox(
		final ItemManager itemManager,
		@Nullable final String title,
		final Supplier<List<BankTrackerItem>> itemsSupplier) {
		this.itemManager = itemManager;
		this.itemsSupplier = itemsSupplier;

		setLayout(new BorderLayout(0, 1));
		setBorder(new EmptyBorder(5, 0, 0, 0));

		logTitle.setLayout(new BoxLayout(logTitle, BoxLayout.X_AXIS));
		logTitle.setBorder(new EmptyBorder(7, 7, 7, 7));

		JLabel titleLabel = new JLabel();
		titleLabel.setText(title);
		titleLabel.setFont(FontManager.getRunescapeSmallFont());
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setMinimumSize(new Dimension(1, titleLabel.getPreferredSize().height));
		logTitle.add(titleLabel);

		logTitle.add(Box.createRigidArea(new Dimension(TITLE_PADDING, 0)));
		logTitle.add(Box.createHorizontalGlue());
		logTitle.add(Box.createRigidArea(new Dimension(TITLE_PADDING, 0)));

		add(logTitle, BorderLayout.NORTH);
		add(itemContainer, BorderLayout.CENTER);
	}

	void rebuild() {
		buildItems();
		revalidate();
	}

	private void buildItems()
	{
		var bankItems = itemsSupplier.get();
		if (bankItems.size() == 0) {
			setVisible(false);
			itemContainer.removeAll();
			itemContainer.revalidate();
			return;
		}
		setVisible(true);

		// Calculates how many rows need to be display to fit all items
		final int rowSize = ((bankItems.size() % ITEMS_PER_ROW == 0) ? 0 : 1) + bankItems.size() / ITEMS_PER_ROW;

		itemContainer.removeAll();
		itemContainer.setLayout(new GridLayout(rowSize, ITEMS_PER_ROW, 1, 1));

		final Border favoriteBorder = new LineBorder(ColorScheme.BRAND_ORANGE_TRANSPARENT);
		for (int i = 0; i < rowSize * ITEMS_PER_ROW; i++)
		{
			final JPanel slotContainer = new JPanel();
			slotContainer.setBackground(ColorScheme.DARKER_GRAY_COLOR);

			if (i < bankItems.size())
			{
				final BankTrackerItem bankItem = bankItems.get(i);
				final Rs2Item item = bankItem.getItem();
				if (bankItem.isFavorite()) {
					slotContainer.setBorder(favoriteBorder);
				}
				final JLabel imageLabel = new JLabel();
				imageLabel.addMouseListener(new MouseAdapter() {
					@Override
					public void mouseClicked(MouseEvent e) {
						bankItem.toggleFavorite();
					}
				});
				imageLabel.setToolTipText(buildToolTip(bankItem));
				imageLabel.setVerticalAlignment(SwingConstants.CENTER);
				imageLabel.setHorizontalAlignment(SwingConstants.CENTER);

				AsyncBufferedImage itemImage = itemManager.getImage(item.getId(), item.quantity, item.quantity > 1);
				itemImage.addTo(imageLabel);

				slotContainer.add(imageLabel);
			}

			itemContainer.add(slotContainer);
		}

		itemContainer.revalidate();
	}

	private static String buildToolTip(BankTrackerItem bankItem) {
		final Rs2Item item = bankItem.getItem();
		final String name = item.getName();
		final int quantity = item.quantity;
		final long gePrice = item.getPrice();
		final long haPrice = getHaPrice(item) * quantity;
		final StringBuilder sb = new StringBuilder("<html>");
		sb.append(name).append(" x ").append(QuantityFormatter.formatNumber(quantity));
		if (item.getId() == ItemID.COINS_995)
		{
			sb.append("</html>");
			return sb.toString();
		}

		sb.append("<br>GE: ").append(QuantityFormatter.quantityToStackSize(gePrice));
		if (quantity > 1)
		{
			sb.append(" (").append(QuantityFormatter.quantityToStackSize(gePrice/quantity)).append(" ea)");
		}

		if (item.getId() == ItemID.PLATINUM_TOKEN)
		{
			sb.append("</html>");
			return sb.toString();
		}

		sb.append("<br>HA: ").append(QuantityFormatter.quantityToStackSize(haPrice));
		if (quantity > 1)
		{
			sb.append(" (").append(QuantityFormatter.quantityToStackSize(getHaPrice(item))).append(" ea)");
		}
		sb.append("</html>");
		return sb.toString();
	}

	private static int getHaPrice(Rs2Item item) {
		return Microbot.getItemManager().getItemComposition(item.id).getHaPrice();
	}
}
