package com.oheers.fish.baits;

import com.oheers.fish.EvenMoreFish;
import com.oheers.fish.utils.nbt.NbtKeys;
import com.oheers.fish.utils.nbt.NbtUtils;
import com.oheers.fish.config.MainConfig;
import com.oheers.fish.config.messages.ConfigMessage;
import com.oheers.fish.config.messages.Message;
import com.oheers.fish.exceptions.MaxBaitReachedException;
import com.oheers.fish.exceptions.MaxBaitsReachedException;
import com.oheers.fish.utils.nbt.NbtVersion;
import de.tr7zw.changeme.nbtapi.NBTCompound;
import de.tr7zw.changeme.nbtapi.NBTItem;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

public class BaitListener implements Listener {

    @EventHandler
    public void onClickEvent(InventoryClickEvent event) {
        if (event.getCurrentItem() == null || event.getCursor() == null) {
            return;
        }

        if (MainConfig.getInstance().shouldProtectBaitedRods() && anvilCheck(event)) {
            return;
        }

        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (clickedItem.getType() != Material.FISHING_ROD)
            return;

        if (!BaitNBTManager.isBaitObject(cursor)) {
            return;
        }


        if (!event.getWhoClicked().getGameMode().equals(GameMode.SURVIVAL)) {
            new Message(ConfigMessage.BAIT_WRONG_GAMEMODE).broadcast(event.getWhoClicked(), false);
            return;
        }

        ApplicationResult result = null;
        Bait bait = EvenMoreFish.getInstance().getBaits().get(BaitNBTManager.getBaitName(event.getCursor()));

        ItemStack fishingRod = clickedItem;
        NbtVersion nbtVersion = NbtVersion.getVersion(clickedItem);
        if (nbtVersion != NbtVersion.COMPAT) {
            fishingRod = convertToCompatNbtItem(nbtVersion, fishingRod);
        }

        try {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                result = BaitNBTManager.applyBaitedRodNBT(fishingRod, bait, event.getCursor().getAmount());
                EvenMoreFish.getInstance().incrementMetricBaitsApplied(event.getCursor().getAmount());
            } else {
                result = BaitNBTManager.applyBaitedRodNBT(fishingRod, bait, 1);
                EvenMoreFish.getInstance().incrementMetricBaitsApplied(1);
            }

        } catch (MaxBaitsReachedException exception) {
            new Message(ConfigMessage.BAITS_MAXED).broadcast(event.getWhoClicked(), false);
            result = exception.getRecoveryResult();
        } catch (MaxBaitReachedException exception) {
            result = exception.getRecoveryResult();
            Message message = new Message(ConfigMessage.BAITS_MAXED_ON_ROD);
            message.setBaitTheme(bait.getTheme());
            message.setBait(bait.getName());
            message.broadcast(event.getWhoClicked(), true);
        }

        if (result == null || result.getFishingRod() == null)
            return;


        event.setCancelled(true);
        event.setCurrentItem(result.getFishingRod());

        int cursorModifier = result.getCursorItemModifier();

        if (cursor.getAmount() - cursorModifier == 0) {
            event.getWhoClicked().setItemOnCursor(new ItemStack(Material.AIR));
        } else {
            cursor.setAmount(cursor.getAmount() + cursorModifier);
            event.getWhoClicked().setItemOnCursor(cursor);
        }
    }

    private ItemStack convertToCompatNbtItem(final NbtVersion nbtVersion, final ItemStack fishingRod) {
        NBTItem nbtFishingRod = new NBTItem(fishingRod);
        final String appliedBaitString = NbtUtils.getString(fishingRod, NbtKeys.EMF_APPLIED_BAIT);

        if (nbtVersion == NbtVersion.LEGACY) {
            final String namespacedKey = NbtKeys.EMF_COMPOUND + ":" + NbtKeys.EMF_APPLIED_BAIT;
            nbtFishingRod.getCompound(NbtKeys.PUBLIC_BUKKIT_VALUES).removeKey(namespacedKey);

            if (Boolean.TRUE.equals(nbtFishingRod.hasKey(namespacedKey))) { //bugged version
                nbtFishingRod.removeKey(namespacedKey);
                nbtFishingRod.getCompound("display").setObject("Lore", null);
            }
        }

        if (nbtVersion == NbtVersion.NBTAPI) {
            nbtFishingRod.removeKey(NbtKeys.EMF_COMPOUND + ":" + NbtKeys.EMF_APPLIED_BAIT);
        }

        NBTCompound emfCompound = nbtFishingRod.getOrCreateCompound(NbtKeys.EMF_COMPOUND);
        emfCompound.setString(NbtKeys.EMF_APPLIED_BAIT, appliedBaitString);
        return nbtFishingRod.getItem();
    }

    private boolean anvilCheck(InventoryClickEvent event) {
        if (!(event.getClickedInventory() instanceof AnvilInventory) || !(event.getWhoClicked() instanceof Player)) {
            return false;
        }
        Player player = (Player) event.getWhoClicked();
        AnvilInventory inv = (AnvilInventory) event.getClickedInventory();
        if (event.getSlot() == 2 && BaitNBTManager.isBaitedRod(inv.getItem(1))) {
            event.setCancelled(true);
            player.closeInventory();
            new Message(ConfigMessage.BAIT_ROD_PROTECTION).broadcast(player, false);
            return true;
        }
        return false;
    }

}
