package com.simibubi.create.content.logistics.stockTicker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.filter.FilterItem;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Keeps Stock Ticker category mutations server-authoritative. Client packets describe intent only;
 * every stored or refunded stack is resolved from server-owned state.
 */
final class StockKeeperCategorySecurity {

	private static final int MAX_EDITED_NAME_LENGTH = 28;
	private static final int MAX_TRACKED_REJECTION_PLAYERS = 1024;
	private static final long REJECTION_LOG_INTERVAL_MS = 2000;
	private static final Map<UUID, Long> LAST_REJECTION_LOG = new LinkedHashMap<>(128, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
			return size() > MAX_TRACKED_REJECTION_PLAYERS;
		}
	};

	private StockKeeperCategorySecurity() {}

	/**
	 * Binds a mutation packet to the exact, currently open server menu and rejects stale concurrent editors.
	 */
	static StockKeeperCategoryMenu validateSession(ServerPlayer player, StockTickerBlockEntity be, String packetName) {
		if (!(player.containerMenu instanceof StockKeeperCategoryMenu menu) || menu.contentHolder != be) {
			reject(player, be, packetName, "no_matching_category_menu");
			return null;
		}
		if (!menu.stillValid(player) || !be.behaviour.mayInteract(player)) {
			reject(player, be, packetName, "category_menu_no_longer_valid");
			return null;
		}
		if (!menu.matchesCategoryRevision(be.getCategoryRevision())) {
			reject(player, be, packetName, "stale_category_session");
			return null;
		}
		return menu;
	}

	/**
	 * Removes one matching server-owned category before returning that same server-owned stack.
	 */
	static boolean refund(ServerPlayer player, StockTickerBlockEntity be, StockKeeperCategoryMenu menu,
		ItemStack requestedFilter) {
		if (requestedFilter.isEmpty() || requestedFilter.getCount() != 1
			|| !(requestedFilter.getItem() instanceof FilterItem)) {
			reject(player, be, "refund", "invalid_filter_request");
			return false;
		}

		int index = findExactCategory(be.categories, requestedFilter);
		if (index == -1)
			index = findCategoryIgnoringCustomName(be.categories, requestedFilter);
		if (index == -1) {
			reject(player, be, "refund", "filter_not_present_in_server_categories");
			return false;
		}

		ItemStack refund = be.categories.remove(index).copyWithCount(1);
		be.incrementCategoryRevision();
		menu.acknowledgeCategoryRevision(be.getCategoryRevision());
		player.getInventory().placeItemBackInInventory(refund);
		be.notifyUpdate();
		return true;
	}

	/**
	 * Resolves every requested entry from an existing category, the player's inventory, or the carried stack.
	 * Client-provided stacks are used only for matching and the UI-supported custom-name change.
	 */
	static boolean applySchedule(ServerPlayer player, StockTickerBlockEntity be, StockKeeperCategoryMenu menu,
		List<ItemStack> requestedSchedule) {
		Inventory inventory = player.getInventory();

		// Plan the entire mutation before consuming or refunding anything.
		List<ItemStack> existing = be.categories.stream()
			.map(stack -> stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1))
			.toList();
		boolean[] usedExisting = new boolean[existing.size()];
		int[] inventoryUsage = new int[inventory.getContainerSize()];
		ItemStack[] inventorySources = new ItemStack[inventory.getContainerSize()];
		int carriedUsage = 0;
		List<ItemStack> acceptedSchedule = new ArrayList<>(requestedSchedule.size());

		for (ItemStack requested : requestedSchedule) {
			if (requested.isEmpty()) {
				int emptyIndex = findUnusedEmptyCategory(existing, usedExisting);
				if (emptyIndex == -1) {
					reject(player, be, "edit", "new_empty_category");
					return false;
				}
				usedExisting[emptyIndex] = true;
				acceptedSchedule.add(ItemStack.EMPTY);
				continue;
			}
			if (requested.getCount() != 1 || !(requested.getItem() instanceof FilterItem)) {
				reject(player, be, "edit", "invalid_category_entry");
				return false;
			}

			ItemStack source = takeMatchingExisting(existing, usedExisting, requested);
			if (source.isEmpty())
				source = reserveMatchingInventory(inventory, inventoryUsage, inventorySources, requested);
			if (source.isEmpty() && carriedUsage < menu.getCarried().getCount()
				&& sameFilterDataIgnoringCustomName(menu.getCarried(), requested)) {
				source = menu.getCarried();
				carriedUsage++;
			}
			if (source.isEmpty()) {
				reject(player, be, "edit", "requested_filter_not_owned_by_server_or_player");
				return false;
			}

			ItemStack accepted = applyRequestedName(source, requested);
			if (accepted.isEmpty()) {
				reject(player, be, "edit", "invalid_category_name_change");
				return false;
			}
			acceptedSchedule.add(accepted);
		}

		// Recheck every reserved source immediately before committing the transaction.
		if (!inventoryReservationsStillValid(inventory, inventoryUsage, inventorySources)) {
			reject(player, be, "edit", "inventory_changed_during_validation");
			return false;
		}
		// Commit: consume new sources and replace server state before returning removed categories.
		for (int slot = 0; slot < inventoryUsage.length; slot++)
			if (inventoryUsage[slot] > 0)
				inventory.removeItem(slot, inventoryUsage[slot]);
		if (carriedUsage > 0)
			menu.getCarried().shrink(carriedUsage);

		be.categories = acceptedSchedule;
		be.incrementCategoryRevision();
		menu.acknowledgeCategoryRevision(be.getCategoryRevision());

		for (int i = 0; i < existing.size(); i++) {
			ItemStack oldCategory = existing.get(i);
			if (!usedExisting[i] && !oldCategory.isEmpty())
				inventory.placeItemBackInInventory(oldCategory.copyWithCount(1));
		}

		be.notifyUpdate();
		return true;
	}

	private static int findUnusedEmptyCategory(List<ItemStack> existing, boolean[] usedExisting) {
		for (int i = 0; i < existing.size(); i++)
			if (!usedExisting[i] && existing.get(i).isEmpty())
				return i;
		return -1;
	}

	private static ItemStack takeMatchingExisting(List<ItemStack> existing, boolean[] usedExisting, ItemStack requested) {
		for (int i = 0; i < existing.size(); i++) {
			ItemStack candidate = existing.get(i);
			if (usedExisting[i] || candidate.isEmpty() || !sameFilterDataIgnoringCustomName(candidate, requested))
				continue;
			usedExisting[i] = true;
			return candidate;
		}
		return ItemStack.EMPTY;
	}

	private static ItemStack reserveMatchingInventory(Inventory inventory, int[] inventoryUsage,
		ItemStack[] inventorySources, ItemStack requested) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack candidate = inventory.getItem(slot);
			if (candidate.isEmpty() || candidate.getCount() <= inventoryUsage[slot]
				|| !sameFilterDataIgnoringCustomName(candidate, requested))
				continue;
			if (inventorySources[slot] == null)
				inventorySources[slot] = candidate.copyWithCount(1);
			inventoryUsage[slot]++;
			return candidate;
		}
		return ItemStack.EMPTY;
	}

	private static boolean inventoryReservationsStillValid(Inventory inventory, int[] inventoryUsage, ItemStack[] inventorySources) {
		for (int slot = 0; slot < inventoryUsage.length; slot++) {
			if (inventoryUsage[slot] == 0)
				continue;
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || stack.getCount() < inventoryUsage[slot]
				|| !ItemStack.isSameItemSameTags(stack.copyWithCount(1), inventorySources[slot]))
				return false;
		}
		return true;
	}

	private static ItemStack applyRequestedName(ItemStack source, ItemStack requested) {
		ItemStack accepted = source.copyWithCount(1);
		if (ItemStack.isSameItemSameTags(accepted, requested.copyWithCount(1)))
			return accepted;

		String requestedName = requested.hasCustomHoverName() ? requested.getHoverName().getString() : "";
		if (requestedName.length() > MAX_EDITED_NAME_LENGTH)
			return ItemStack.EMPTY;
		accepted.setHoverName(requestedName.isBlank() ? null : Component.literal(requestedName));
		return accepted;
	}

	private static int findExactCategory(List<ItemStack> categories, ItemStack requested) {
		for (int i = 0; i < categories.size(); i++) {
			ItemStack category = categories.get(i);
			if (!category.isEmpty() && category.getCount() == 1 && ItemStack.isSameItemSameTags(category, requested))
				return i;
		}
		return -1;
	}

	private static int findCategoryIgnoringCustomName(List<ItemStack> categories, ItemStack requested) {
		for (int i = 0; i < categories.size(); i++)
			if (sameFilterDataIgnoringCustomName(categories.get(i), requested))
				return i;
		return -1;
	}

	private static boolean sameFilterDataIgnoringCustomName(ItemStack first, ItemStack second) {
		if (first.isEmpty() || second.isEmpty() || first.getItem() != second.getItem())
			return false;
		ItemStack firstCopy = first.copyWithCount(1);
		ItemStack secondCopy = second.copyWithCount(1);
		firstCopy.setHoverName(null);
		secondCopy.setHoverName(null);
		return ItemStack.isSameItemSameTags(firstCopy, secondCopy);
	}

	static void reject(ServerPlayer player, StockTickerBlockEntity be, String packetName, String reason) {
		long now = System.currentTimeMillis();
		synchronized (LAST_REJECTION_LOG) {
			Long previous = LAST_REJECTION_LOG.put(player.getUUID(), now);
			if (previous != null && now - previous < REJECTION_LOG_INTERVAL_MS)
				return;
		}
		Create.LOGGER.warn(
			"[StockTickerSecurity] Rejected {} packet from {} ({}) at {}: {}",
			packetName, player.getGameProfile().getName(), player.getUUID(), be.getBlockPos(), reason);
	}

}