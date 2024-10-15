package net.hynse.eXMNT

import io.papermc.paper.ban.BanListType
import me.nahu.scheduler.wrapper.FoliaWrappedJavaPlugin
import me.nahu.scheduler.wrapper.WrappedScheduler
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.util.Date
import java.util.UUID

class EXMNT : FoliaWrappedJavaPlugin(), Listener {
    private var modConfigs: MutableList<ModDetectionConfig> = mutableListOf()
    private val originalBlockData = HashMap<UUID, BlockData>()

    private var notifyStaff: Boolean = true
    private var notificationPermission: String = "exmnt.notify"

    companion object {
        lateinit var instance: FoliaWrappedJavaPlugin
            private set
        val wrappedScheduler: WrappedScheduler by lazy { instance.scheduler }
    }

    override fun onEnable() {
        instance = this
        saveDefaultConfig()
        loadConfig()
        server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
    }

    private fun loadConfig() {
        reloadConfig()
        notifyStaff = config.getBoolean("notifyStaff", true)
        notificationPermission = config.getString("notificationPermission", "exmnt.notify") ?: "exmnt.notify"
        loadModConfigs()
    }

    private fun loadModConfigs() {
        modConfigs.clear()
        val modsSection = config.getList("mods")
        if (modsSection == null) {
            logger.warning("No 'mods' section found in the configuration.")
            return
        }

        for (modEntry in modsSection) {
            if (modEntry !is Map<*, *>) {
                logger.warning("Invalid mod entry: $modEntry")
                continue
            }

            val modName = modEntry.keys.firstOrNull()?.toString()
            if (modName == null) {
                logger.warning("Mod name not found in entry: $modEntry")
                continue
            }

            val modConfig = modEntry[modName]
            if (modConfig !is Map<*, *>) {
                logger.warning("Invalid configuration for mod '$modName'")
                continue
            }

            val detectConfig = modConfig["detect"] as? Map<*, *>
            val punishmentConfig = modConfig["punishment"] as? Map<*, *>

            if (detectConfig == null || punishmentConfig == null) {
                logger.warning("Missing 'detect' or 'punishment' configuration for mod '$modName'")
                continue
            }

            val key = detectConfig["key"]?.toString()
            val detectionKey = detectConfig["detectionKey"]?.toString()

            if (key == null || detectionKey == null) {
                logger.warning("Missing 'key' or 'detectionKey' for mod '$modName'")
                continue
            }

            val action = try {
                ModAction.valueOf((punishmentConfig["action"]?.toString() ?: "NOTICE").uppercase())
            } catch (_: IllegalArgumentException) {
                logger.warning("Invalid action for mod '$modName': ${punishmentConfig["action"]}")
                ModAction.NOTICE
            }

            val reason = punishmentConfig["reason"]?.toString()
            val duration = punishmentConfig["duration"]?.toString()

            val modDetectionConfig = ModDetectionConfig(
                name = modName,
                key = key,
                detectionKey = detectionKey,
                action = action,
                reason = reason,
                duration = duration
            )

            modConfigs.add(modDetectionConfig)
            logger.info("Loaded configuration for mod: $modName")
        }

        logger.info("Loaded ${modConfigs.size} mod configurations")
    }

    @EventHandler (priority = EventPriority.HIGHEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        wrappedScheduler.runTaskLater( {
            openSignEditor(event.player)
        }, 1L)
    }

    @EventHandler
    fun onSignChange(event: SignChangeEvent) {
        wrappedScheduler.runTaskAtLocation(event.block.location, {
            val player = event.player
            val lines = event.lines()

//        logger.info("Sign content after closed:\n${lines.joinToString("\n") { "Line ${lines.indexOf(it)}: $it" }}")

            val signContent = lines[0].toString()
            modConfigs.forEach { config ->
                if (signContent.contains("T: ${config.detectionKey}")) {
                    handleModDetection(player, config)
                }
            }
            val uuid = player.uniqueId
            val blockData = originalBlockData[uuid]
            if (blockData != null) {
                event.block.blockData = blockData
                originalBlockData.remove(uuid)
            }
        })
    }

    private fun openSignEditor(player: Player) {
        try {
            wrappedScheduler.runTaskAtLocation(player.location, {
            val signLocation = player.location.clone().add(0.0, -5.0, 0.0)
            val block = signLocation.block
            val originalBlockType = block.blockData
            val uuid = player.uniqueId
            originalBlockData[uuid] = originalBlockType

            block.type = Material.OAK_SIGN
            val sign = block.state as Sign
            val backSide = sign.getSide(Side.BACK)

            val content = Component.text("").toBuilder()
            modConfigs.forEach { config ->
                content.append(Component.text(" T: ").append(Component.translatable(config.key)))
            }

            backSide.line(0, content.build())

            sign.update()
            sign.allowedEditorUniqueId = uuid
//            logger.info("Sign content before opening:\n${backSide.lines().joinToString("\n") { "Line ${backSide.lines().indexOf(it)}: $it" }}")

            wrappedScheduler.runTaskLaterAtEntity(player, {
                player.openSign(sign, Side.BACK)
                player.closeInventory()

                }, 7L)
            })
        } catch (e: Exception) {
            logger.severe("Failed to open sign editor: ${e.message}")
        }
    }
    private fun handleModDetection(player: Player, config: ModDetectionConfig) {
        when (config.action) {
            ModAction.NOTICE -> {
                logger.info("Player ${player.name} is using ${config.name}")
                notifyStaff("Player ${player.name} is using ${config.name}")
            }
            ModAction.KICK -> {
                player.kick(Component.text(config.reason ?: "Kicked for using ${config.name}"))
                logger.info("Player ${player.name} was kicked for using ${config.name}")
                notifyStaff("Player ${player.name} was kicked for using ${config.name}")
            }
            ModAction.BAN -> {
                val banList = Bukkit.getBanList(BanListType.PROFILE)
                val expires = parseDuration(config.duration)
                val reason = config.reason ?: "Banned for using ${config.name}"
                val source = "EXMNT Plugin"

                banList.addBan(player.playerProfile, reason, expires, source)
                player.kick(Component.text("Banned: $reason"))
                logger.info("Player ${player.name} was banned for using ${config.name}")
                notifyStaff("Player ${player.name} was banned for using ${config.name}")
            }
            ModAction.IGNORE -> {
                // Do nothing
            }
        }
    }

    private fun parseDuration(duration: String?): Date? {
        if (duration == null) return null
        val amount = duration.substring(0, duration.length - 1).toLongOrNull() ?: return null
        return when (duration.last()) {
            'd' -> Date(System.currentTimeMillis() + amount * 24 * 60 * 60 * 1000)
            'h' -> Date(System.currentTimeMillis() + amount * 60 * 60 * 1000)
            'm' -> Date(System.currentTimeMillis() + amount * 60 * 1000)
            else -> null
        }
    }

    data class ModDetectionConfig(
        val name: String,
        val key: String,
        val detectionKey: String,
        val action: ModAction,
        val reason: String? = null,
        val duration: String? = null,
    )

    enum class ModAction {
        NOTICE, KICK, BAN, IGNORE
    }
    private fun notifyStaff(message: String) {
        if (!notifyStaff) return
        val staffNotification = Component.text("[EXMNT] $message", NamedTextColor.RED)
        Bukkit.getOnlinePlayers().forEach { onlinePlayer ->
            if (onlinePlayer.hasPermission(notificationPermission)) {
                onlinePlayer.sendMessage(staffNotification)
            }
        }
    }
}