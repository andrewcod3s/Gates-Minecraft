package me.geonations.drawBridge

import me.geonations.drawBridge.commands.GateCommand
import me.geonations.drawBridge.commands.GateAdminCommand
import me.geonations.drawBridge.gate.GateManager
import org.bukkit.plugin.java.JavaPlugin

class DrawBridgePlugin : JavaPlugin() {

    lateinit var gateManager: GateManager
        private set

    override fun onEnable() {
        // Initialize managers
        gateManager = GateManager(this)

        // Register commands
        val gateCommand = GateCommand(this)
        getCommand("gate")?.setExecutor(gateCommand)
        getCommand("gate")?.tabCompleter = gateCommand

        val gateAdminCommand = GateAdminCommand(this)
        getCommand("gateadmin")?.setExecutor(gateAdminCommand)
        getCommand("gateadmin")?.tabCompleter = gateAdminCommand


        // Register events
        server.pluginManager.registerEvents(gateManager, this)

        // Load gates from database
        gateManager.loadGates()

        logger.info("DrawBridge plugin enabled!")
    }

    override fun onDisable() {
        // Clean up all gates
        gateManager.shutdown()
        
        // Close database connection to prevent file locks
        try {
            gateManager.databaseManager.close()
            logger.info("Database connection closed successfully")
        } catch (e: Exception) {
            logger.severe("Failed to close database connection: ${e.message}")
        }

        logger.info("DrawBridge plugin disabled!")
    }
}
