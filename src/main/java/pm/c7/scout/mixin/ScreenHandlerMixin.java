package pm.c7.scout.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.collection.DefaultedList;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import pm.c7.scout.ScoutMixin.Transformer;
import pm.c7.scout.ScoutUtil;
import pm.c7.scout.screen.BagSlot;

@Mixin(value = ScreenHandler.class, priority = 950)
@Transformer(ScreenHandlerTransformer.class)
public abstract class ScreenHandlerMixin {
	@Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
	private void scout$handleBagSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci) {
		if (!ScoutUtil.isBagSlot(slotIndex)) return;

		Slot slot = ScoutUtil.getBagSlot(slotIndex, player.playerScreenHandler);
		if (slot == null) {
			ci.cancel();
			return;
		}

		switch (actionType) {
			case PICKUP -> {
				ItemStack cursorStack = this.getCursorStack();
				ItemStack slotStack = slot.getStack();

				if (cursorStack.isEmpty()) {
					if (!slotStack.isEmpty() && slot.canTakeItems(player)) {
						int takeCount = button == 0 ? slotStack.getCount() : (slotStack.getCount() + 1) / 2;
						ItemStack taken = slot.takeStack(takeCount);
						this.setCursorStack(taken);
						slot.markDirty();
					}
				} else {
					if (slotStack.isEmpty()) {
						if (slot.canInsert(cursorStack)) {
							int placeCount = button == 0 ? cursorStack.getCount() : 1;
							placeCount = Math.min(placeCount, slot.getMaxItemCount());
							slot.setStackNoCallbacks(cursorStack.split(placeCount));
						}
					} else if (slot.canInsert(cursorStack) && ItemStack.areItemsAndComponentsEqual(slotStack, cursorStack)) {
						int placeCount = button == 0 ? cursorStack.getCount() : 1;
						int maxCount = Math.min(slot.getMaxItemCount(), cursorStack.getMaxCount());
						int room = maxCount - slotStack.getCount();
						placeCount = Math.min(placeCount, room);
						cursorStack.decrement(placeCount);
						slotStack.increment(placeCount);
						slot.setStackNoCallbacks(slotStack);
					} else if (slot.canTakeItems(player) && slot.canInsert(cursorStack)) {
						if (cursorStack.getCount() <= slot.getMaxItemCount()) {
							slot.setStackNoCallbacks(cursorStack);
							this.setCursorStack(slotStack);
						}
					}
				}
				ci.cancel();
			}
			case THROW -> {
				if (!this.getCursorStack().isEmpty()) {
					ci.cancel();
					return;
				}
				ItemStack slotStack = slot.getStack();
				if (!slotStack.isEmpty() && slot.canTakeItems(player)) {
					int dropCount = button == 0 ? 1 : slotStack.getCount();
					ItemStack dropped = slot.takeStack(dropCount);
					player.dropItem(dropped, true);
					slot.markDirty();
				}
				ci.cancel();
			}
			case SWAP -> {
				PlayerInventory inv = player.getInventory();
				ItemStack hotbarStack = button == 40 ? inv.getStack(40) : inv.getStack(button);
				ItemStack slotStack = slot.getStack();

				if (!hotbarStack.isEmpty() || !slotStack.isEmpty()) {
					if (hotbarStack.isEmpty()) {
						if (slot.canTakeItems(player)) {
							if (button == 40) inv.setStack(40, slotStack);
							else inv.setStack(button, slotStack);
							slot.setStackNoCallbacks(ItemStack.EMPTY);
						}
					} else if (slotStack.isEmpty()) {
						if (slot.canInsert(hotbarStack)) {
							int maxCount = slot.getMaxItemCount();
							if (hotbarStack.getCount() > maxCount) {
								slot.setStackNoCallbacks(hotbarStack.split(maxCount));
							} else {
								if (button == 40) inv.setStack(40, ItemStack.EMPTY);
								else inv.setStack(button, ItemStack.EMPTY);
								slot.setStackNoCallbacks(hotbarStack);
							}
						}
					} else if (slot.canTakeItems(player) && slot.canInsert(hotbarStack)) {
						int maxCount = slot.getMaxItemCount();
						if (hotbarStack.getCount() > maxCount) {
							slot.setStackNoCallbacks(hotbarStack.split(maxCount));
							if (!inv.insertStack(slotStack)) {
								player.dropItem(slotStack, true);
							}
						} else {
							if (button == 40) inv.setStack(40, slotStack);
							else inv.setStack(button, slotStack);
							slot.setStackNoCallbacks(hotbarStack);
						}
					}
				}
				ci.cancel();
			}
			case QUICK_MOVE -> {
				ItemStack slotStack = slot.getStack();
				if (!slotStack.isEmpty() && slot.canTakeItems(player)) {
					ItemStack taken = slot.takeStack(slotStack.getCount());
					ItemStack remainder = this.insertItem(taken, player);
					if (!remainder.isEmpty()) {
						slot.setStackNoCallbacks(remainder);
					}
					slot.markDirty();
				}
				ci.cancel();
			}
			default -> ci.cancel();
		}
	}

	private ItemStack insertItem(ItemStack stack, PlayerEntity player) {
		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < 36 && !stack.isEmpty(); i++) {
			ItemStack invStack = inv.getStack(i);
			if (ItemStack.areItemsAndComponentsEqual(invStack, stack)) {
				int room = invStack.getMaxCount() - invStack.getCount();
				int toMove = Math.min(stack.getCount(), room);
				invStack.increment(toMove);
				stack.decrement(toMove);
			}
		}
		for (int i = 0; i < 36 && !stack.isEmpty(); i++) {
			if (inv.getStack(i).isEmpty()) {
				inv.setStack(i, stack.copy());
				stack.setCount(0);
			}
		}
		return stack;
	}

	@Inject(method = "internalOnSlotClick", at = @At(value = "INVOKE_ASSIGN", target = "Lnet/minecraft/screen/ScreenHandler;getCursorStack()Lnet/minecraft/item/ItemStack;", ordinal = 11), locals = LocalCapture.CAPTURE_FAILEXCEPTION)
	public void scout$fixDoubleClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player, CallbackInfo ci, PlayerInventory playerInventory, Slot slot3) {
		var cursorStack = this.getCursorStack();
		if (!cursorStack.isEmpty() && (!slot3.hasStack() || !slot3.canTakeItems(player))) {
			var slots = ScoutUtil.getAllBagSlots(player.playerScreenHandler);
			var k = button == 0 ? 0 : ScoutUtil.TOTAL_SLOTS - 1;
			var o = button == 0 ? 1 : -1;

			for (int n = 0; n < 2; ++n) {
				for (int p = k; p >= 0 && p < slots.size() && cursorStack.getCount() < cursorStack.getMaxCount(); p += o) {
					Slot slot4 = slots.get(p);
					if (slot4.hasStack() && canInsertItemIntoSlot(slot4, cursorStack, true) && slot4.canTakeItems(player) && this.canInsertIntoSlot(cursorStack, slot4)) {
						ItemStack itemStack6 = slot4.getStack();
						if (n != 0 || itemStack6.getCount() != itemStack6.getMaxCount()) {
							ItemStack itemStack7 = slot4.takeStackRange(itemStack6.getCount(), cursorStack.getMaxCount() - cursorStack.getCount(), player);
							cursorStack.increment(itemStack7.getCount());
						}
					}
				}
			}
		}
	}

	@Dynamic("Workaround for Debugify. Other calls are modified via the attached transformer class.")
	@Redirect(method = "internalOnSlotClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/collection/DefaultedList;get(I)Ljava/lang/Object;", ordinal = 5))
	public Object scout$fixSlotIndexing(DefaultedList<Slot> self, int index, int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
		if (ScoutUtil.isBagSlot(index)) {
			return ScoutUtil.getBagSlot(index, player.playerScreenHandler);
		} else {
			return self.get(index);
		}
	}

	@Inject(method = "canInsertIntoSlot(Lnet/minecraft/screen/slot/Slot;)Z", at = @At("HEAD"), cancellable = true)
	private void scout$preventBagSlotDrag(Slot slot, CallbackInfoReturnable<Boolean> cir) {
		if (slot instanceof BagSlot) {
			cir.setReturnValue(false);
		}
	}

	@Shadow
	public static boolean canInsertItemIntoSlot(@Nullable Slot slot, ItemStack stack, boolean allowOverflow) {
		return false;
	}
	@Shadow
	public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
		return true;
	}
	@Shadow
	public abstract ItemStack getCursorStack();
	@Shadow
	public abstract void setCursorStack(ItemStack stack);
}
