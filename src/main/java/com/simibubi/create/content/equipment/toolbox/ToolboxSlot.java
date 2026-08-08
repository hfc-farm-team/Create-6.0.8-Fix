package com.simibubi.create.content.equipment.toolbox;

import net.minecraft.world.item.ItemStack;

import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;

public class ToolboxSlot extends SlotItemHandler {

	private ToolboxMenu toolboxMenu;

	public ToolboxSlot(ToolboxMenu menu, IItemHandler itemHandler, int index, int xPosition, int yPosition) {
		super(itemHandler, index, xPosition, yPosition);
		this.toolboxMenu = menu;
	}

	@Override
	public boolean isActive() {
		return !toolboxMenu.renderPass && super.isActive();
	}

	@Override
	public int getMaxStackSize() {
		return 64;
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return Math.min(64, stack.getMaxStackSize()); // 兼顾不可堆叠或小堆叠物品（如末影珍珠、工具）
	}

}
