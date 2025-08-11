package me.geonations.drawBridge.commands

import me.geonations.drawBridge.DrawBridgePlugin
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class GateAdminCommand(private val plugin: DrawBridgePlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("gate.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.")
            return true
        }

        if (args.isEmpty() || args[0].lowercase() != "cleanup") {
            sender.sendMessage("§cUsage: /gateadmin cleanup")
            return true
        }

        sender.sendMessage("§aStarting comprehensive cleanup of all gate-related blocks...")
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.gateManager.deleteAllGateBlocks()
            sender.sendMessage("§aComprehensive cleanup completed! Check console for details.")
        })
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size == 1) {
            return mutableListOf("cleanup").filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }
        return mutableListOf()
    }
}