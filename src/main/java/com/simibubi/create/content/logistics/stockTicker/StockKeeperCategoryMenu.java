package com.simibubi.create.content.logistics.stockTicker;

import org.jetbrains.annotations.NotNull;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllMenuTypes;
import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.foundation.gui.menu.MenuBase;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

public class StockKeeperCategoryMenu extends MenuBase<StockTickerBlockEntity> {

	public boolean slotsActive = true;
	public ItemStackHandler proxyInventory;
	private long categoryRevision;

	public StockKeeperCategoryMenu(MenuType<?> type, int id, Inventory inv, FriendlyByteBuf extraData) {
		super(type, id, inv, extraData);
	}

	public static AbstractContainerMenu create(int pContainerId, Inventory pPlayerInventory,
		StockTickerBlockEntity stockTickerBlockEntity) {
		return new StockKeeperCategoryMenu(AllMenuTypes.STOCK_KEEPER_CATEGORY.get(), pContainerId, pPlayerInventory,
			stockTickerBlockEntity);
	}

	public StockKeeperCategoryMenu(MenuType<?> type, int id, Inventory inv, StockTickerBlockEntity contentHolder) {
		super(type, id, inv, contentHolder);
	}

	@Override
	protected void initAndReadInventory(StockTickerBlockEntity contentHolder) {
		proxyInventory = new ItemStackHandler(1);
		categoryRevision = contentHolder.getCategoryRevision();
	}

	@Override
	protected StockTickerBlockEntity createOnClient(FriendlyByteBuf extraData) {
		BlockPos blockPos = extraData.readBlockPos();
		return AllBlocks.STOCK_TICKER.get()
			.getBlockEntity(Minecraft.getInstance().level, blockPos);
	}

	@Override
	protected void addSlots() {
		addSlot(new InactiveItemHandlerSlot(proxyInventory, 0, 16, 24));
		addPlayerSlots(18, 106);
	}

	@Override
	protected Slot createPlayerSlot(Inventory inventory, int index, int x, int y) {
		return new InactiveSlot(inventory, index, x, y);
	}

	@Override
	protected void saveData(StockTickerBlockEntity contentHolder) {
		proxyInventory.setStackInSlot(0, ItemStack.EMPTY);
	}

	boolean matchesCategoryRevision(long revision) {
		return categoryRevision == revision;
	}

	void acknowledgeCategoryRevision(long revision) {
		categoryRevision = revision;
	}

	@Override
	public boolean stillValid(Player player) {
		return !contentHolder.isRemoved() && player.position()
			.closerThan(Vec3.atCenterOf(contentHolder.getBlockPos()), player.getBlockReach() + 4);
	}

	class InactiveSlot extends Slot {

		public InactiveSlot(Container pContainer, int pIndex, int pX, int pY) {
			super(pContainer, pIndex, pX, pY);
		}

		@Override
		public boolean isActive() {
			return slotsActive;
		}

	}

	class InactiveItemHandlerSlot extends SlotItemHandler {

		public InactiveItemHandlerSlot(IItemHandler itemHandler, int index, int xPosition, int yPosition) {
			super(itemHandler, index, xPosition, yPosition);
		}

		@Override
		public boolean mayPlace(@NotNull ItemStack stack) {
			return super.mayPlace(stack) && (stack.isEmpty() || stack.getItem() instanceof FilterItem);
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}

		@Override
		public boolean isActive() {
			return slotsActive;
		}

	}

	@Override
	public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
		if (slotId != 0) {
			super.clicked(slotId, dragType, clickType, player);
			return;
		}
		if (clickType == ClickType.THROW)
			return;

		ItemStack held = getCarried();
		ItemStack ghost = held.isEmpty() || !(held.getItem() instanceof FilterItem)
			? ItemStack.EMPTY
			: held.copyWithCount(1);
		proxyInventory.setStackInSlot(0, ghost);
		getSlot(0).setChanged();
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
		return slot != getSlot(0) && super.canTakeItemForPickAll(stack, slot);
	}

	@Override
	public boolean canDragTo(Slot slot) {
		return slot != getSlot(0) && super.canDragTo(slot);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		if (index == 0) {
			proxyInventory.setStackInSlot(0, ItemStack.EMPTY);
			getSlot(0).setChanged();
			return ItemStack.EMPTY;
		}

		Slot clickedSlot = getSlot(index);
		if (!clickedSlot.hasItem() || !(clickedSlot.getItem().getItem() instanceof FilterItem))
			return ItemStack.EMPTY;
		proxyInventory.setStackInSlot(0, clickedSlot.getItem().copyWithCount(1));
		getSlot(0).setChanged();
		return ItemStack.EMPTY;
	}

}