package dev.anton.villagerLogs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantInventory
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("DEPRECATION")
class VillagerLogs : JavaPlugin(), Listener {

    private val openTrades: MutableMap<UUID, VillagerContext> = ConcurrentHashMap()

    override fun onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this)
        logger.info("VillagerLogs enabled.")
    }

    override fun onDisable() {
        openTrades.clear()
        logger.info("VillagerLogs disabled.")
    }

    // ===== Utility for proper villager name =====
    private fun villagerName(v: AbstractVillager): String {
        return when {
            v.customName != null -> v.customName!!
            v is Villager -> v.profession.key().value()
            v is WanderingTrader -> "WANDERING_TRADER"
            else -> v.type.name
        }
    }

    // ===== VILLAGER TRADE TRACKING =====

    @EventHandler
    fun onPlayerInteractVillager(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val villager = event.rightClicked as? AbstractVillager ?: return
        val player = event.player

        val ctx = VillagerContext(
            uuid = villager.uniqueId,
            name = villagerName(villager),
            type = villager.type,
            location = villager.location
        )

        openTrades[player.uniqueId] = ctx
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (event.inventory.type == InventoryType.MERCHANT && event.player is Player) {
            openTrades.remove(event.player.uniqueId)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        openTrades.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onVillagerTrade(event: InventoryClickEvent) {
        if (event.isCancelled) return
        val player = event.whoClicked as? Player ?: return

        val view: InventoryView = event.view
        if (view.topInventory.type != InventoryType.MERCHANT) return
        if (event.slotType != InventoryType.SlotType.RESULT) return

        val result = event.currentItem ?: return
        if (result.type.isAir) return

        val merchantInventory = view.topInventory as? MerchantInventory ?: return

        val paid1 = merchantInventory.getItem(0)
        val paid2 = merchantInventory.getItem(1)

        val paidStr = itemsToString(paid1, paid2)
        val resultStr = itemToString(result)

        val ctx = openTrades[player.uniqueId]

        val (villagerInfo, world, coords) = if (ctx != null) {
            val loc = ctx.location
            val worldName = loc.world?.name ?: "unknown_world"
            val posString = "x=%.1f y=%.1f z=%.1f".format(loc.x, loc.y, loc.z)
            Triple(
                "Villager '${ctx.name}' (type=${ctx.type.name}, uuid=${ctx.uuid})",
                worldName,
                posString
            )
        } else {
            val loc = player.location
            val worldName = loc.world?.name ?: "unknown_world"
            val posString = "x=%.1f y=%.1f z=%.1f".format(loc.x, loc.y, loc.z)
            Triple("Villager <unknown>", worldName, posString)
        }

        logger.info(
            "[VillagerTrade] Player '${player.name}' traded with $villagerInfo at $world ($coords) – " +
                    "PAID [$paidStr] – RECEIVED [$resultStr]"
        )
    }

    // ===== VILLAGER DEATH LOGGING =====

    @EventHandler
    fun onVillagerDeath(event: EntityDeathEvent) {
        val villager = event.entity as? AbstractVillager ?: return

        val loc = villager.location
        val world = loc.world?.name ?: "unknown_world"
        val coords = "x=%.1f y=%.1f z=%.1f".format(loc.x, loc.y, loc.z)

        val vName = villagerName(villager)

        val damageEvent = villager.lastDamageCause as? EntityDamageByEntityEvent
        var killer: Entity? = null

        if (damageEvent != null) {
            killer = when (val d = damageEvent.damager) {
                is Projectile -> d.shooter as? Entity
                else -> d
            }
        }

        val killerType = killer?.type?.name ?: "UNKNOWN"
        val killerName = when (killer) {
            is Player -> killer.name
            null -> "UNKNOWN"
            else -> killer.customName ?: killer.type.name
        }
        val killerUuid = killer?.uniqueId?.toString() ?: "UNKNOWN"

        logger.info(
            "[VillagerDeath] Villager '$vName' (type=${villager.type.name}, uuid=${villager.uniqueId}) " +
                    "died at $world ($coords). Killer: type=$killerType, name=$killerName, uuid=$killerUuid"
        )
    }

    // ===== UTILS =====

    private fun itemsToString(vararg stacks: ItemStack?): String {
        val parts = stacks.filterNotNull()
            .filter { !it.type.isAir && it.amount > 0 }
            .map { itemToString(it) }

        return if (parts.isEmpty()) "nothing" else parts.joinToString(", ")
    }

    private fun itemToString(stack: ItemStack?): String {
        if (stack == null || stack.type.isAir || stack.amount <= 0) return "nothing"

        val name = if (stack.itemMeta?.hasDisplayName() == true)
            stack.itemMeta!!.displayName
        else
            stack.type.name

        return "${stack.amount}x $name"
    }

    // ===== CONTEXT =====

    private data class VillagerContext(
        val uuid: UUID,
        val name: String,
        val type: EntityType,
        val location: Location
    )
}