package me.geonations.drawBridge.commands

import me.geonations.drawBridge.DrawBridgePlugin
import me.geonations.drawBridge.gate.GateType
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class GateCommand(private val plugin: DrawBridgePlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("This command can only be used by players.")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("§cUsage: /gate <create|remove|cancel|addlever|removelever|listgates>")
            return true
        }

        when (args[0].lowercase()) {
            "create" -> {
                val gateType = if (args.size > 1) {
                    try {
                        GateType.valueOf(args[1].uppercase())
                    } catch (e: IllegalArgumentException) {
                        sender.sendMessage("§cInvalid gate type. Available types: NORMAL, DOUBLE_DOOR")
                        return true
                    }
                } else {
                    GateType.NORMAL
                }
                
                // Start gate creation with the specified gate type
                plugin.gateManager.startGateCreation(sender, gateType)
            }
            "remove" -> {
                val targetBlock = sender.getTargetBlock(null, 5)
                if (plugin.gateManager.removeGateByLocation(sender, targetBlock.location) == false) {
                     sender.sendMessage("§cYou are not looking at a gate control block.")
                 }
            }
            "cancel" -> {
                plugin.gateManager.cancelGateCreation(sender)
            }
            "addlever", "addbutton", "addmechanism" -> {
                plugin.gateManager.startLeverSelection(sender)
            }
            "removelever", "removebutton", "removemechanism" -> {
                val targetBlock = sender.getTargetBlock(null, 5)
                if (!plugin.gateManager.removeLeverFromGate(sender, targetBlock.location)) {
                    sender.sendMessage("§cYou are not looking at a gate lever/button/mechanism.")
                }
            }
            "listgates" -> {
                val targetBlock = sender.getTargetBlock(null, 5)
                if (plugin.gateManager.listGateLinksByLocation(sender, targetBlock.location) == false) {
                    sender.sendMessage("§cYou are not looking at a gate lever/mechanism.")
                }
            }
            else -> sender.sendMessage("§cUnknown subcommand. Usage: /gate <create|remove|cancel|addlever|removelever|listgates>")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        if (args.size == 1) {
            return mutableListOf("create", "remove", "cancel", "addlever", "removelever", "listgates").filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
        }
        if (args.size == 2 && args[0].equals("create", ignoreCase = true)) {
            return mutableListOf("NORMAL", "DOUBLE_DOOR").filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
        }
        return mutableListOf()
    }
}
