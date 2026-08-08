package com.simibubi.create.foundation.gui.menu;

import com.simibubi.create.content.logistics.filter.FilterItem;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperCategoryMenu;
import com.simibubi.create.foundation.networking.SimplePacketBase;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent.Context;

public class GhostItemSubmitPacket extends SimplePacketBase {

	private final ItemStack item;
	private final int slot;

	public GhostItemSubmitPacket(ItemStack item, int slot) {
		this.item = item;
		this.slot = slot;
	}

	public GhostItemSubmitPacket(FriendlyByteBuf buffer) {
		item = buffer.readItem();
		slot = buffer.readInt();
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeItem(item);
		buffer.writeInt(slot);
	}

	@Override
	public boolean handle(Context context) {
		context.enqueueWork(() -> {
			ServerPlayer player = context.getSender();
			if (player == null)
				return;

			if (player.containerMenu instanceof GhostItemMenu<?> menu) {
				if (slot < 0 || slot >= menu.ghostInventory.getSlots())
					return;
				menu.ghostInventory.setStackInSlot(slot, item);
				menu.getSlot(36 + slot)
					.setChanged();
				return;
			}

			if (!(player.containerMenu instanceof StockKeeperCategoryMenu menu) || slot != 0)
				return;
			if (!item.isEmpty() && !(item.getItem() instanceof FilterItem))
				return;
			menu.proxyInventory.setStackInSlot(0, item.isEmpty() ? ItemStack.EMPTY : item.copyWithCount(1));
			menu.getSlot(0)
				.setChanged();
		});
		return true;
	}

}