package me.geonations.drawBridge.gate

import me.geonations.drawBridge.DrawBridgePlugin
import me.geonations.drawBridge.database.DatabaseManager
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import phonon.nodes.NodesAPI
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class GateType {
    NORMAL,
    DOUBLE_DOOR,
    LEVER
}

class GateManager(private val plugin: DrawBridgePlugin) : Listener {

    fun isGateBlock(location: Location): Boolean {
        return blockToGateMap.containsKey(location)
    }

    private val activeGates = mutableMapOf<Location, Gate>()
    private val creationSelections = mutableMapOf<UUID, Location?>()
    private val leverSelectionMode = mutableSetOf<UUID>() // Track players in lever selection mode
    private val blockDisplays = mutableMapOf<Gate, MutableList<BlockDisplay>>()
    internal val blockToGateMap = mutableMapOf<Location, Gate>()
    val databaseManager = DatabaseManager(plugin)
    private val gatesToSave = mutableSetOf<UUID>()
    private var saveTask: BukkitRunnable? = null

    data class Gate(
        val id: UUID,
        val owner: UUID,
        val corner1: Location,
        val corner2: Location,
        val blockData: Map<Location, BlockData>,
        val type: GateType = GateType.NORMAL,
        var isOpen: Boolean = false,
        var isAnimating: Boolean = false,
        val linkedMechanisms: MutableSet<Location> = mutableSetOf()
    ) {
        // Calculate the center point of the gate
        fun getCenter(): Location {
            val world = corner1.world
            val x = (corner1.x + corner2.x) / 2
            val y = (corner1.y + corner2.y) / 2
            val z = (corner1.z + corner2.z) / 2
            return Location(world, x, y, z)
        }
    }

    // Map to store the gate type for each player's creation process
    private val creationGateTypes = mutableMapOf<UUID, GateType>()

    fun startGateCreation(player: Player, gateType: GateType = GateType.NORMAL) {
        if (!NodesAPI.isPlayerInOwnTerritory(player)) {
            player.sendMessage("§cYou can only create gates in your own territory.")
            return
        }
        creationSelections[player.uniqueId] = null
        creationGateTypes[player.uniqueId] = gateType
        player.sendMessage("§aStarted ${gateType.name.lowercase().replace('_', ' ')} gate creation. Right-click the first corner block.")
    }

    fun cancelGateCreation(player: Player) {
        val wasInCreationMode = creationSelections.remove(player.uniqueId) != null
        val wasInLeverMode = leverSelectionMode.remove(player.uniqueId)
        
        if (wasInCreationMode) {
            creationGateTypes.remove(player.uniqueId)
            player.sendMessage("§aGate creation cancelled.")
        } else if (wasInLeverMode) {
            player.sendMessage("§aLever selection mode cancelled.")
        } else {
            player.sendMessage("§cYou have no active gate creation or lever selection process.")
        }
    }

    fun startLeverSelection(player: Player) {
        if (!NodesAPI.isPlayerInOwnTerritory(player)) {
            player.sendMessage("§cYou can only manage mechanisms in your own territory.")
            return
        }
        leverSelectionMode.add(player.uniqueId)
        player.sendMessage("§aRight-click a lever or button, then right-click a gate to link them.")
        player.sendMessage("§7Use /gate cancel to exit selection mode.")
    }

    fun removeLeverFromGate(player: Player, location: Location): Boolean {
        val gate = findGateByBlock(location) ?: return false
        
        if (gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou don't have permission to modify this gate.")
            return false
        }
        
        if (gate.linkedMechanisms.remove(location)) {
            databaseManager.saveGateLinks(gate.id, gate.linkedMechanisms)
            player.sendMessage("§aLever removed from gate.")
            return true
        }
        
        return false
    }

    fun listGateLinksByLocation(player: Player, location: Location): Boolean {
        val gate = findGateByBlock(location) ?: return false
        
        if (gate.linkedMechanisms.isEmpty()) {
            player.sendMessage("§cThis gate has no linked levers.")
            return true
        }
        
        player.sendMessage("§aLinked levers for this gate:")
        gate.linkedMechanisms.forEachIndexed { index, leverLoc ->
            player.sendMessage("§7${index + 1}. ${leverLoc.world.name} (${leverLoc.blockX}, ${leverLoc.blockY}, ${leverLoc.blockZ})")
        }
        
        return true
    }

    fun removeGateByLocation(player: Player, location: Location): Boolean {
        val gate = findGateByBlock(location) ?: return false
        
        if (gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou don't have permission to remove this gate.")
            return false
        }
        
        forceRemoveGate(gate, player)
        return true
    }

    private fun createGate(player: Player, corner1: Location, corner2: Location, gateType: GateType = GateType.NORMAL) {
        if (!NodesAPI.isPlayerInOwnTerritory(player)) {
            player.sendMessage("§cYou can only create gates in your territory.")
            return
        }

        if (abs(corner1.blockX - corner2.blockX) > 0 && abs(corner1.blockZ - corner2.blockZ) > 0) {
            player.sendMessage("§cGates must be aligned with either the X or Z axis.")
            return
        }

        // Check dimensions for double door type
        if (gateType == GateType.DOUBLE_DOOR) {
            val widthX = abs(corner1.blockX - corner2.blockX) + 1
            val widthZ = abs(corner1.blockZ - corner2.blockZ) + 1
            
            // For double doors, the width along the axis of the door must be even
            // This is because the door splits in the middle and each half moves in opposite directions
            val isXAxis = widthX > widthZ
            val axisWidth = if (isXAxis) widthX else widthZ
            
            // The axis width must be even for double doors to split properly
            if (axisWidth % 2 != 0) {
                player.sendMessage("§cDouble doors require even width along the main axis.")
                player.sendMessage("§cCurrent width: ${axisWidth} blocks")
                return
            }
        }

        // Check for existing gates in the area
        val minX = minOf(corner1.blockX, corner2.blockX)
        val maxX = maxOf(corner1.blockX, corner2.blockX)
        val minY = minOf(corner1.blockY, corner2.blockY)
        val maxY = maxOf(corner1.blockY, corner2.blockY)
        val minZ = minOf(corner1.blockZ, corner2.blockZ)
        val maxZ = maxOf(corner1.blockZ, corner2.blockZ)

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val checkLocation = Location(corner1.world, x.toDouble(), y.toDouble(), z.toDouble())
                    if (blockToGateMap.containsKey(checkLocation)) {
                        player.sendMessage("§cThere's already a gate in this area.")
                        return
                    }
                }
            }
        }

        val gateId = UUID.randomUUID()
        val blocks = mutableMapOf<Location, BlockData>()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val blockLocation = Location(corner1.world, x.toDouble(), y.toDouble(), z.toDouble())
                    val block = blockLocation.block
                    if (block.type != Material.AIR) {
                        blocks[blockLocation] = block.blockData
                        // Temporarily set to air to prevent duping
                        block.type = Material.AIR
                    }
                }
            }
        }

        if (blocks.isEmpty()) {
            player.sendMessage("§cNo blocks found in the selected region to create a gate.")
            return
        }

        val gate = Gate(gateId, player.uniqueId, corner1, corner2, blocks, gateType)
        activeGates[corner1] = gate
        activeGates[corner2] = gate
        blocks.keys.forEach { blockToGateMap[it] = gate }
        
        // Save to database
        databaseManager.saveGate(gate)

        // Restore blocks to their original positions (gate starts closed)
        gate.blockData.forEach { (location, blockData) ->
            location.block.blockData = blockData
        }

        player.sendMessage("§aGate created successfully from ${blocks.size} blocks!")
    }

    fun toggleGate(player: Player, location: Location): Boolean {
        val gate = findGateByBlock(location) ?: return false

        if (gate.isAnimating) {
            player.sendMessage("§cThe gate is already moving.")
            return false
        }
        
        if (gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou don't have permission to operate this gate.")
            return false
        }
        if (!NodesAPI.isPlayerInOwnTerritory(player)) {
            player.sendMessage("§cYou can only operate gates in your territory.")
            return false
        }

        // Check integrity before operating
        if (databaseManager.checkForIntegrityViolations(gate)) {
            player.sendMessage("§cGate integrity check failed. Removing corrupted gate...")
            forceRemoveGate(gate, player)
            return false
        }

        if (isPathObstructed(gate)) {
            player.sendMessage("§cSomething is blocking the gate's path.")
            return false
        }

        toggleGateAnimation(gate)
        databaseManager.saveGate(gate)
        return true
    }

    private fun isPathObstructed(gate: Gate): Boolean {
        return when (gate.type) {
            GateType.NORMAL -> isNormalPathObstructed(gate)
            GateType.DOUBLE_DOOR -> isDoubleDoorPathObstructed(gate)
            GateType.LEVER -> false // Levers don't have a path
        }
    }
    
    private fun isNormalPathObstructed(gate: Gate): Boolean {
        val moveDistance = (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble()
        val locationsToClear = if (!gate.isOpen) {
            gate.blockData.keys.map { it.clone().add(0.0, moveDistance, 0.0) }
        } else {
            gate.blockData.keys
        }

        for (loc in locationsToClear) {
            if (loc.block.type != Material.AIR) {
                return true
            }
        }
        return false
    }
    
    private fun isDoubleDoorPathObstructed(gate: Gate): Boolean {
        // Determine if the gate is aligned along X or Z axis
        val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
        
        // Calculate the center point of the gate
        val centerX = (gate.corner1.blockX + gate.corner2.blockX) / 2.0
        val centerZ = (gate.corner1.blockZ + gate.corner2.blockZ) / 2.0
        
        // Calculate the maximum distance blocks need to move (half the gate width along the axis)
        // For X-axis gates, blocks move along the X-axis (left/right)
        // For Z-axis gates, blocks move along the Z-axis (forward/backward)
        val moveDistance = if (isXAxis) {
            (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
        } else {
            (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
        }
        
        val locationsToClear = mutableListOf<Location>()
        
        gate.blockData.keys.forEach { originalLocation ->
            // Determine which side of the center this block is on
            val side = if (isXAxis) {
                if (originalLocation.x < centerX) -1 else 1
            } else {
                if (originalLocation.z < centerZ) -1 else 1
            }
            
            // If opening, check the target locations; if closing, check the original locations
            if (!gate.isOpen) {
                // Check where blocks will move to when opening
                val targetLocation = if (isXAxis) {
                    originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
                } else {
                    originalLocation.clone().add(0.0, 0.0, side * moveDistance)
                }
                locationsToClear.add(targetLocation)
            } else {
                // When closing, check the original positions
                locationsToClear.add(originalLocation)
            }
        }
        
        for (loc in locationsToClear) {
            if (loc.block.type != Material.AIR) {
                return true
            }
        }
        return false
    }

    private fun toggleGateAnimation(gate: Gate) {
        when (gate.type) {
            GateType.NORMAL -> toggleNormalGateAnimation(gate)
            GateType.DOUBLE_DOOR -> toggleDoubleDoorAnimation(gate)
            GateType.LEVER -> {}
        }
    }

    private fun toggleNormalGateAnimation(gate: Gate) {
        val moveDistance = (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble()
        // Increase duration for smoother animation (was 60.0)  
        val duration = 40.0 // Faster animation (2 seconds instead of 5)

        gate.isAnimating = true
        val tempDisplays = mutableMapOf<Location, BlockDisplay>()
        val barrierLocations = mutableListOf<Location>()

        val isOpening = !gate.isOpen

        val initialBlockLocations = if (isOpening) {
            gate.blockData.keys
        } else {
            gate.blockData.keys.map { it.clone().add(0.0, moveDistance, 0.0) }
        }

        initialBlockLocations.forEachIndexed { index, loc ->
            val originalLocation = gate.blockData.keys.elementAt(index)
            val blockData = gate.blockData[originalLocation]!!
            
            // Verify block integrity before animation
            if (loc.block.type != Material.AIR && loc.block.type != Material.BARRIER) {
                loc.block.type = Material.AIR
            }
            
            val display = loc.world.spawn(loc, BlockDisplay::class.java) {
                it.block = blockData
            }
            tempDisplays[originalLocation] = display
        }

        gate.isOpen = isOpening

        object : BukkitRunnable() {
            var progress = 0.0
            override fun run() {
                barrierLocations.forEach { it.block.type = Material.AIR }
                barrierLocations.clear()
                
                if (progress >= 1.0) {
                    tempDisplays.forEach { (originalLocation, display) ->
                        val finalLocation = if (gate.isOpen) {
                            originalLocation.clone().add(0.0, moveDistance, 0.0)
                        } else {
                            originalLocation
                        }
                        
                        // Final integrity check
                        if (finalLocation.block.type != Material.AIR && finalLocation.block.type != Material.BARRIER) {
                            plugin.logger.warning("Block collision at ${finalLocation}, removing gate")
                            forceRemoveGate(gate, null)
                            display.remove()
                            return
                        }
                        
                        finalLocation.block.blockData = display.block
                        display.remove()
                    }
                    gate.isAnimating = false
                    updateBlockMap(gate)
                    
                    // Schedule gate state save (deferred)
                    scheduleSave(gate)
                    
                    cancel()
                    return
                }

                progress += 1.0 / duration
                val easedProgress = easeInOutQuart(progress)

                tempDisplays.forEach { (originalLocation, display) ->
                    val yOffset = if (gate.isOpen) {
                        easedProgress * moveDistance
                    } else {
                        (1 - easedProgress) * moveDistance
                    }
                    val newLocation = originalLocation.clone().add(0.0, yOffset, 0.0)
                    display.teleport(newLocation)

                    val barrierLoc = newLocation.block.location
                    if (barrierLoc.block.type == Material.AIR) {
                        barrierLoc.block.type = Material.BARRIER
                        barrierLocations.add(barrierLoc)
                    }
                }
            }
        // Run every tick for maximum smoothness
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun toggleDoubleDoorAnimation(gate: Gate) {
        // Determine if the gate is aligned along X or Z axis
        val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
        
        // Calculate the center point of the gate
        val centerX = (gate.corner1.blockX + gate.corner2.blockX) / 2.0
        val centerZ = (gate.corner1.blockZ + gate.corner2.blockZ) / 2.0
        
        // Calculate the maximum distance blocks need to move (half the gate width along the axis)
        // For X-axis gates, blocks move along the X-axis (left/right)
        // For Z-axis gates, blocks move along the Z-axis (forward/backward)
        val moveDistance = if (isXAxis) {
            (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
        } else {
            (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
        }
        
        // Faster animation for smoother experience
        val duration = 40.0 // 2 seconds for faster, smoother animation
        gate.isAnimating = true
        val tempDisplays = mutableMapOf<Location, BlockDisplay>()
        val barrierLocations = mutableListOf<Location>()
        
        val isOpening = !gate.isOpen
        
        // For each block, determine which side of the center it's on and calculate its target position
        gate.blockData.keys.forEach { originalLocation ->
            val blockData = gate.blockData[originalLocation]!!
            
            // Determine which side of the center this block is on
            val side = if (isXAxis) {
                if (originalLocation.x < centerX) -1 else 1
            } else {
                if (originalLocation.z < centerZ) -1 else 1
            }
            
            // Calculate the current position based on whether the gate is open or closed
            val currentLocation = if (!isOpening) {
                // If we're closing, blocks start at their open position
                if (isXAxis) {
                    originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
                } else {
                    originalLocation.clone().add(0.0, 0.0, side * moveDistance)
                }
            } else {
                // If we're opening, blocks start at their original position
                originalLocation.clone()
            }
            
            // Verify block integrity before animation
            if (currentLocation.block.type != Material.AIR && currentLocation.block.type != Material.BARRIER) {
                currentLocation.block.type = Material.AIR
            }
            
            // Create the block display entity
            val display = currentLocation.world.spawn(currentLocation, BlockDisplay::class.java) {
                it.block = blockData
            }
            tempDisplays[originalLocation] = display
        }
        
        gate.isOpen = isOpening
        
        object : BukkitRunnable() {
            var progress = 0.0
            override fun run() {
                barrierLocations.forEach { it.block.type = Material.AIR }
                barrierLocations.clear()
                
                if (progress >= 1.0) {
                    tempDisplays.forEach { (originalLocation, display) ->
                        // Determine which side of the center this block is on
                        val side = if (isXAxis) {
                            if (originalLocation.x < centerX) -1 else 1
                        } else {
                            if (originalLocation.z < centerZ) -1 else 1
                        }
                        
                        // Calculate final location
                        val finalLocation = if (gate.isOpen) {
                            if (isXAxis) {
                                originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
                            } else {
                                originalLocation.clone().add(0.0, 0.0, side * moveDistance)
                            }
                        } else {
                            originalLocation.clone()
                        }
                        
                        // Final integrity check
                        if (finalLocation.block.type != Material.AIR && finalLocation.block.type != Material.BARRIER) {
                            plugin.logger.warning("Block collision at ${finalLocation}, removing gate")
                            forceRemoveGate(gate, null)
                            display.remove()
                            return
                        }
                        
                        finalLocation.block.blockData = display.block
                        display.remove()
                    }
                    gate.isAnimating = false
                    updateBlockMap(gate)
                    
                    // Schedule gate state save (deferred)
                    scheduleSave(gate)
                    
                    cancel()
                    return
                }
                
                progress += 1.0 / duration
                val easedProgress = easeInOutQuart(progress)
                
                tempDisplays.forEach { (originalLocation, display) ->
                    // Determine which side of the center this block is on
                    val side = if (isXAxis) {
                        if (originalLocation.x < centerX) -1 else 1
                    } else {
                        if (originalLocation.z < centerZ) -1 else 1
                    }
                    
                    // Calculate the offset based on progress
                    val offset = if (gate.isOpen) {
                        easedProgress * moveDistance * side
                    } else {
                        (1 - easedProgress) * moveDistance * side
                    }
                    
                    // Apply the offset to create the new location
                    val newLocation = if (isXAxis) {
                        originalLocation.clone().add(offset, 0.0, 0.0)
                    } else {
                        originalLocation.clone().add(0.0, 0.0, offset)
                    }
                    
                    display.teleport(newLocation)
                    
                    // Add barrier blocks to prevent entities from passing through
                    val barrierLoc = newLocation.block.location
                    if (barrierLoc.block.type == Material.AIR) {
                        barrierLoc.block.type = Material.BARRIER
                        barrierLocations.add(barrierLoc)
                    }
                }
            }
        // Run every tick for maximum smoothness
        }.runTaskTimer(plugin, 0L, 1L)
    }
    
    private fun easeInOutCubic(t: Double): Double {
        return if (t < 0.5) 4 * t * t * t else 1 - Math.pow(-2 * t + 2, 3.0) / 2
    }
    
    private fun easeInOutQuart(t: Double): Double {
        return if (t < 0.5) 8 * t * t * t * t else 1 - Math.pow(-2 * t + 2, 4.0) / 2
    }

    fun removeGate(player: Player, location: Location): Boolean {
        val gate = findGateByBlock(location) ?: return false
        
        if (gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou don't have permission to remove this gate.")
            return false
        }

        // Check integrity before removal
        if (databaseManager.checkForIntegrityViolations(gate)) {
            player.sendMessage("§cGate integrity check failed. Removing corrupted gate...")
            forceRemoveGate(gate, player)
            return true
        }

        // Restore blocks to their original positions based on gate type
        when (gate.type) {
            GateType.NORMAL -> restoreNormalGateBlocks(gate)
            GateType.DOUBLE_DOOR -> restoreDoubleDoorGateBlocks(gate)
            GateType.LEVER -> {}
        }
        
        activeGates.remove(gate.corner1)
        activeGates.remove(gate.corner2)
        
        // Clear the block to gate mapping
        blockToGateMap.entries.removeIf { it.value == gate }

        // Remove from database
        databaseManager.deleteGate(gate.id)

        player.sendMessage("§aGate removed successfully!")
        return true
    }
    
    private fun restoreNormalGateBlocks(gate: Gate) {
        if (gate.isOpen) {
            val moveDistance = (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble()
            gate.blockData.forEach { (loc, data) ->
                val openLoc = loc.clone().add(0.0, moveDistance, 0.0)
                openLoc.block.type = Material.AIR
                loc.block.blockData = data
            }
        }
    }
    
    private fun restoreDoubleDoorGateBlocks(gate: Gate) {
        if (gate.isOpen) {
            // Determine if the gate is aligned along X or Z axis
            val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
            
            // Calculate the center point of the gate
            val centerX = (gate.corner1.blockX + gate.corner2.blockX) / 2.0
            val centerZ = (gate.corner1.blockZ + gate.corner2.blockZ) / 2.0
            
            // Calculate the maximum distance blocks need to move (half the gate width along the axis)
            // For X-axis gates, blocks move along the X-axis (left/right)
            // For Z-axis gates, blocks move along the Z-axis (forward/backward)
            val moveDistance = if (isXAxis) {
                (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
            } else {
                (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
            }
            
            gate.blockData.forEach { (originalLocation, data) ->
                // Determine which side of the center this block is on
                val side = if (isXAxis) {
                    if (originalLocation.x < centerX) -1 else 1
                } else {
                    if (originalLocation.z < centerZ) -1 else 1
                }
                
                // Calculate the open location
                val openLoc = if (isXAxis) {
                    originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
                } else {
                    originalLocation.clone().add(0.0, 0.0, side * moveDistance)
                }
                
                // Clear the open location and restore the block to its original position
                openLoc.block.type = Material.AIR
                originalLocation.block.blockData = data
            }
        }
    }

    private fun forceRemoveGate(gate: Gate, player: Player?) {
        // Force removal without block restoration for corrupted gates
        activeGates.remove(gate.corner1)
        activeGates.remove(gate.corner2)
        
        when (gate.type) {
            GateType.NORMAL -> {
                // For normal gates, handle vertical movement
                val moveDistance = (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble()
                gate.blockData.keys.forEach { 
                    val openLoc = it.clone().add(0.0, if (gate.isOpen) moveDistance else 0.0, 0.0)
                    blockToGateMap.remove(it) 
                    blockToGateMap.remove(openLoc)
                }
            }
            GateType.DOUBLE_DOOR -> {
                // Determine if the gate is aligned along X or Z axis
                val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
                
                // Calculate the center point of the gate
                val centerX = (gate.corner1.blockX + gate.corner2.blockX) / 2.0
                val centerZ = (gate.corner1.blockZ + gate.corner2.blockZ) / 2.0
                
                // Calculate the maximum distance blocks need to move (half the gate width along the axis)
                // For X-axis gates, blocks move along the X-axis (left/right)
                // For Z-axis gates, blocks move along the Z-axis (forward/backward)
                val moveDistance = if (isXAxis) {
                    (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
                } else {
                    (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
                }
                
                gate.blockData.keys.forEach { originalLocation ->
                    // Determine which side of the center this block is on
                    val side = if (isXAxis) {
                        if (originalLocation.x < centerX) -1 else 1
                    } else {
                        if (originalLocation.z < centerZ) -1 else 1
                    }
                    
                    // Calculate the open location
                    val openLoc = if (isXAxis) {
                        originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
                    } else {
                        originalLocation.clone().add(0.0, 0.0, side * moveDistance)
                    }
                    
                    blockToGateMap.remove(originalLocation)
                    blockToGateMap.remove(openLoc)
                }
            }
            GateType.LEVER -> {
                // LEVER gates don't have movement, just clean up mappings
                gate.blockData.keys.forEach { location ->
                    blockToGateMap.remove(location)
                }
            }
        }
        
        databaseManager.deleteGate(gate.id)
        player?.sendMessage("§aCorrupted gate has been removed.")
    }

    private fun findGateByBlock(location: Location): Gate? {
        val loc = Location(location.world, location.blockX.toDouble(), location.blockY.toDouble(), location.blockZ.toDouble())
        return blockToGateMap[loc]
    }
    
    private fun findGateWithLinkedLever(leverLocation: Location): Gate? {
        // Normalize the location to block coordinates
        val normalizedLoc = Location(leverLocation.world, leverLocation.blockX.toDouble(), leverLocation.blockY.toDouble(), leverLocation.blockZ.toDouble())
        
        // Search through all gates to find one with this lever linked
        return activeGates.values.distinct().find { gate ->
            gate.linkedMechanisms.any { mechLoc -> 
                mechLoc.world == normalizedLoc.world &&
                mechLoc.blockX == normalizedLoc.blockX &&
                mechLoc.blockY == normalizedLoc.blockY &&
                mechLoc.blockZ == normalizedLoc.blockZ
            }
        }
    }
    
    private fun toggleGateViaLever(player: Player, gate: Gate): Boolean {
        // Skip territory check for lever operation - allows remote operation
        
        if (gate.isAnimating) {
            player.sendMessage("§cThe gate is already moving.")
            return false
        }
        
        // Check integrity before operating
        if (databaseManager.checkForIntegrityViolations(gate)) {
            player.sendMessage("§cGate integrity check failed. Removing corrupted gate...")
            forceRemoveGate(gate, player)
            return false
        }

        if (isPathObstructed(gate)) {
            player.sendMessage("§cSomething is blocking the gate's path.")
            return false
        }

        toggleGateAnimation(gate)
        databaseManager.saveGate(gate)
        return true
    }

    private fun updateBlockMap(gate: Gate) {
        blockToGateMap.entries.removeIf { it.value == gate }
        
        when (gate.type) {
            GateType.NORMAL -> updateNormalBlockMap(gate)
            GateType.DOUBLE_DOOR -> updateDoubleDoorBlockMap(gate)
            GateType.LEVER -> {}
        }
    }
    
    private fun updateNormalBlockMap(gate: Gate) {
        val moveDistance = if(gate.isOpen) (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble() else 0.0
        gate.blockData.keys.forEach { loc ->
            val newLoc = loc.clone().add(0.0, moveDistance, 0.0)
            blockToGateMap[newLoc] = gate
        }
    }
    
    private fun updateDoubleDoorBlockMap(gate: Gate) {
        // Determine if the gate is aligned along X or Z axis
        val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
        
        // Calculate the center point of the gate
        val centerX = (gate.corner1.blockX + gate.corner2.blockX) / 2.0
        val centerZ = (gate.corner1.blockZ + gate.corner2.blockZ) / 2.0
        
        // Calculate the maximum distance blocks need to move (half the gate width along the axis)
        // For X-axis gates, blocks move along the X-axis (left/right)
        // For Z-axis gates, blocks move along the Z-axis (forward/backward)
        val moveDistance = if (isXAxis) {
            (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
        } else {
            (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
        }
        
        gate.blockData.keys.forEach { originalLocation ->
            // Determine which side of the center this block is on
            val side = if (isXAxis) {
                if (originalLocation.x < centerX) -1 else 1
            } else {
                if (originalLocation.z < centerZ) -1 else 1
            }
            
            // Calculate the new location based on whether the gate is open
            val newLoc = if (gate.isOpen) {
                if (isXAxis) {
                    originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
                } else {
                    originalLocation.clone().add(0.0, 0.0, side * moveDistance)
                }
            } else {
                originalLocation.clone()
            }
            
            blockToGateMap[newLoc] = gate
        }
    }

    private fun cleanupNormalGate(gate: Gate): Pair<Int, Int> {
        var blocksRemoved = 0
        var barrierBlocksRemoved = 0
        val moveDistance = (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble()
        
        // Calculate the complete area including both open and closed positions
        val minX = minOf(gate.corner1.blockX, gate.corner2.blockX)
        val maxX = maxOf(gate.corner1.blockX, gate.corner2.blockX)
        val minY = minOf(gate.corner1.blockY, gate.corner2.blockY) - 1  // Include area below
        val maxY = maxOf(gate.corner1.blockY, gate.corner2.blockY) + moveDistance.toInt() + 1  // Include area above
        val minZ = minOf(gate.corner1.blockZ, gate.corner2.blockZ)
        val maxZ = maxOf(gate.corner1.blockZ, gate.corner2.blockZ)
        
        // Clean up all blocks in the gate area (both open and closed positions)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val location = Location(gate.corner1.world, x.toDouble(), y.toDouble(), z.toDouble())
                    val block = location.block
                    
                    // Remove gate blocks and barrier blocks
                    if (block.type == Material.BARRIER || gate.blockData.containsKey(location) || 
                        gate.blockData.containsKey(location.clone().subtract(0.0, moveDistance, 0.0))) {
                        
                        
                        block.type = Material.AIR
                        blocksRemoved++
                        
                        if (block.type == Material.BARRIER) {
                            barrierBlocksRemoved++
                        }
                    }
                }
            }
        }
        return Pair(blocksRemoved, barrierBlocksRemoved)
    }
    
    private fun cleanupDoubleDoorGate(gate: Gate): Pair<Int, Int> {
        var blocksRemoved = 0
        var barrierBlocksRemoved = 0
        // Determine if the gate is aligned along X or Z axis
        val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
        
    


        
        // Calculate the maximum distance blocks need to move (half the gate width along the axis)
        // For X-axis gates, blocks move along the X-axis (left/right)
        // For Z-axis gates, blocks move along the Z-axis (forward/backward)
        val moveDistance = if (isXAxis) {
            (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
        } else {
            (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
        }
        
        // Calculate the complete area including both open and closed positions
        val minX = minOf(gate.corner1.blockX, gate.corner2.blockX) - (if (isXAxis) moveDistance.toInt() else 0)
        val maxX = maxOf(gate.corner1.blockX, gate.corner2.blockX) + (if (isXAxis) moveDistance.toInt() else 0)
        val minY = minOf(gate.corner1.blockY, gate.corner2.blockY) - 1  // Include area below
        val maxY = maxOf(gate.corner1.blockY, gate.corner2.blockY) + 1  // Include area above
        val minZ = minOf(gate.corner1.blockZ, gate.corner2.blockZ) - (if (!isXAxis) moveDistance.toInt() else 0)
        val maxZ = maxOf(gate.corner1.blockZ, gate.corner2.blockZ) + (if (!isXAxis) moveDistance.toInt() else 0)
        
        // Clean up all blocks in the gate area (both open and closed positions)
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    val location = Location(gate.corner1.world, x.toDouble(), y.toDouble(), z.toDouble())
                    val block = location.block
                    
                    // Remove gate blocks and barrier blocks
                    if (block.type == Material.BARRIER || gate.blockData.containsKey(location)) {
                        
                        block.type = Material.AIR
                        blocksRemoved++
                        
                        if (block.type == Material.BARRIER) {
                            barrierBlocksRemoved++
                        }
                    }
                }
            }
        }
        return Pair(blocksRemoved, barrierBlocksRemoved)
    }
    
    private fun cleanupActiveNormalGate(gate: Gate): Pair<Int, Int> {
        var blocksRemoved = 0
        var barrierBlocksRemoved = 0
        val moveDistance = (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble()
        
        // Clean up original block positions
        gate.blockData.keys.forEach { location ->
            val block = location.block
            if (block.type != Material.AIR) {
                block.type = Material.AIR
                blocksRemoved++
            }
            
            // Clean up open positions
            val openLocation = location.clone().add(0.0, moveDistance, 0.0)
            val openBlock = openLocation.block
            if (openBlock.type != Material.AIR) {
                openBlock.type = Material.AIR
                blocksRemoved++
            }
            
            // Clean up any barrier blocks
            for (y in minOf(location.y, openLocation.y).toInt()..maxOf(location.y, openLocation.y).toInt()) {
                val checkLocation = Location(location.world, location.x, y.toDouble(), location.z)
                val checkBlock = checkLocation.block
                if (checkBlock.type == Material.BARRIER) {
                    checkBlock.type = Material.AIR
                    barrierBlocksRemoved++
                }
            }
        }
        return Pair(blocksRemoved, barrierBlocksRemoved)
    }
        
    private fun cleanupActiveDoubleDoorGate(gate: Gate): Pair<Int, Int> {
        var blocksRemoved = 0
        var barrierBlocksRemoved = 0
        // Determine if the gate is aligned along X or Z axis
        val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
        
        // Calculate the center point of the gate
        val centerX = (gate.corner1.blockX + gate.corner2.blockX) / 2.0
        val centerZ = (gate.corner1.blockZ + gate.corner2.blockZ) / 2.0
        
        // Calculate the maximum distance blocks need to move (half the gate width along the axis)
        // For X-axis gates, blocks move along the X-axis (left/right)
        // For Z-axis gates, blocks move along the Z-axis (forward/backward)
        val moveDistance = if (isXAxis) {
            (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
        } else {
            (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
        }
        
        // Clean up original block positions and potential open positions
        gate.blockData.keys.forEach { location ->
            // Clean original position
            val block = location.block
            if (block.type != Material.AIR) {
                block.type = Material.AIR
                blocksRemoved++
            }
            
            // Determine which side of the center this block is on
            val side = if (isXAxis) {
                if (location.x < centerX) -1 else 1
            } else {
                if (location.z < centerZ) -1 else 1
            }
            
            // Clean up open position
            val openLocation = if (isXAxis) {
                location.clone().add(side * moveDistance, 0.0, 0.0)
            } else {
                location.clone().add(0.0, 0.0, side * moveDistance)
            }
            
            val openBlock = openLocation.block
            if (openBlock.type != Material.AIR) {
                openBlock.type = Material.AIR
                blocksRemoved++
            }
            
            // Clean up any barrier blocks in the path
            if (isXAxis) {
                // For X-axis gates, check along X
                val minX = minOf(location.x, location.x + (side * moveDistance))
                val maxX = maxOf(location.x, location.x + (side * moveDistance))
                for (x in minX.toInt()..maxX.toInt()) {
                    val checkLocation = Location(location.world, x.toDouble(), location.y, location.z)
                    val checkBlock = checkLocation.block
                    if (checkBlock.type == Material.BARRIER) {
                        checkBlock.type = Material.AIR
                        blocksRemoved++
                        barrierBlocksRemoved++
                    }
                }
            } else {
                // For Z-axis gates, check along Z
                val minZ = minOf(location.z, location.z + (side * moveDistance))
                val maxZ = maxOf(location.z, location.z + (side * moveDistance))
                for (z in minZ.toInt()..maxZ.toInt()) {
                    val checkLocation = Location(location.world, location.x, location.y, z.toDouble())
                    val checkBlock = checkLocation.block
                    if (checkBlock.type == Material.BARRIER) {
                        checkBlock.type = Material.AIR
                        blocksRemoved++
                        barrierBlocksRemoved++
                    }
                }
            }
        }
        return Pair(blocksRemoved, barrierBlocksRemoved)
    }
    
    fun shutdown() {
        plugin.logger.info("Saving gate states and cleaning up...")
        
        // Cancel save task if running
        saveTask?.cancel()
        saveTask = null
        
        // Process any pending saves before shutdown
        processPendingSaves()
        
        // Save all gate states to database
        activeGates.values.distinct().forEach { gate ->
            if (gate.isAnimating) {
                // If gate is mid-animation, save the target state
                gate.isAnimating = false
            }
            databaseManager.saveGate(gate)
            databaseManager.saveGateLinks(gate.id, gate.linkedMechanisms)
        }
        
        // Clean up all BlockDisplay entities
        blockDisplays.values.flatten().forEach { entity ->
            entity.remove()
        }
        
        // Clear all maps
        activeGates.clear()
        creationSelections.clear()
        creationGateTypes.clear()
        blockToGateMap.clear()
        blockDisplays.clear()
        gatesToSave.clear()
        
        plugin.logger.info("Gate manager shutdown complete")
    }
    
    private fun scheduleSave(gate: Gate) {
        gatesToSave.add(gate.id)
        
        if (saveTask == null) {
            saveTask = object : BukkitRunnable() {
                override fun run() {
                    processPendingSaves()
                }
            }
            saveTask?.runTaskLater(plugin, 20L) // Save after 1 second (20 ticks)
        }
    }
    
    private fun processPendingSaves() {
        saveTask?.cancel()
        saveTask = null
        
        gatesToSave.toList().forEach { gateId ->
            activeGates.values.find { it.id == gateId }?.let { gate ->
                databaseManager.saveGate(gate)
            }
        }
        gatesToSave.clear()
    }
    
    fun linkGateToMechanism(player: Player, gateLocation: Location, mechanismLocation: Location): Boolean {
        val gate = findGateByBlock(gateLocation) ?: run {
            player.sendMessage("§cNo gate found at this location.")
            return false
        }
        
        if (gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou don't have permission to link this gate.")
            return false
        }
        
        val block = mechanismLocation.block
        if (block.type != Material.LEVER && !block.type.name.contains("BUTTON", ignoreCase = true)) {
            player.sendMessage("§cYou can only link levers and buttons to gates.")
            return false
        }
        
        if (gate.linkedMechanisms.contains(mechanismLocation)) {
            player.sendMessage("§cThis mechanism is already linked to the gate.")
            return false
        }
        
        // Check if the mechanism is within 15 blocks of the gate
        val gateCenter = gate.getCenter()
        val distance = gateCenter.distance(mechanismLocation)
        if (distance > 15.0) {
            player.sendMessage("§cThe mechanism is too far from the gate. Maximum distance is 15 blocks.")
            return false
        }
        
        gate.linkedMechanisms.add(mechanismLocation)
        databaseManager.saveGateLinks(gate.id, gate.linkedMechanisms)
        
        val mechanismType = if (block.type == Material.LEVER) "lever" else "button"
        player.sendMessage("§aSuccessfully linked $mechanismType to the gate! (Distance: ${String.format("%.1f", distance)} blocks)")
        return true
    }
    
    fun unlinkGateFromMechanism(player: Player, gateLocation: Location, mechanismLocation: Location): Boolean {
        val gate = findGateByBlock(gateLocation) ?: run {
            player.sendMessage("§cNo gate found at this location.")
            return false
        }
        
        if (gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou don't have permission to unlink this gate.")
            return false
        }
        
        if (!gate.linkedMechanisms.contains(mechanismLocation)) {
            player.sendMessage("§cThis mechanism is not linked to the gate.")
            return false
        }
        
        gate.linkedMechanisms.remove(mechanismLocation)
        databaseManager.saveGateLinks(gate.id, gate.linkedMechanisms)
        
        val block = mechanismLocation.block
        val mechanismType = if (block.type == Material.LEVER) "lever" else "button"
        player.sendMessage("§aSuccessfully unlinked $mechanismType from the gate!")
        return true
    }
    
    fun listGateLinks(player: Player, gateLocation: Location): Boolean {
        val gate = findGateByBlock(gateLocation) ?: run {
            player.sendMessage("§cNo gate found at this location.")
            return false
        }
        
        if (gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou don't have permission to view this gate's links.")
            return false
        }
        
        if (gate.linkedMechanisms.isEmpty()) {
            player.sendMessage("§aThis gate has no linked mechanisms.")
            return true
        }
        
        player.sendMessage("§aLinked mechanisms for this gate:")
        gate.linkedMechanisms.forEach { location ->
            val block = location.block
            val mechanismType = if (block.type == Material.LEVER) "Lever" else "Button"
            player.sendMessage("§7- $mechanismType at ${location.blockX}, ${location.blockY}, ${location.blockZ}")
        }
        return true
    }

    fun deleteAllGateBlocks() {
        plugin.logger.info("Starting comprehensive cleanup of all gate-related blocks...")

        var totalBlocksRemoved = 0
        var totalBarrierBlocksRemoved = 0

        // Get all gates from database to ensure we clean up everything
        val allGates = databaseManager.loadAllGates()

        allGates.forEach { gate ->
            val (blocks, barriers) = when (gate.type) {
                GateType.NORMAL -> cleanupNormalGate(gate)
                GateType.DOUBLE_DOOR -> cleanupDoubleDoorGate(gate)
                GateType.LEVER -> Pair(0,0)
            }
            totalBlocksRemoved += blocks
            totalBarrierBlocksRemoved += barriers
        }

        // Also clean up any active gates in memory
        activeGates.values.distinct().forEach { gate ->
            val (blocks, barriers) = when (gate.type) {
                GateType.NORMAL -> cleanupActiveNormalGate(gate)
                GateType.DOUBLE_DOOR -> cleanupActiveDoubleDoorGate(gate)
                GateType.LEVER -> Pair(0,0)
            }
            totalBlocksRemoved += blocks
            totalBarrierBlocksRemoved += barriers
        }
        
        // Remove all BlockDisplay entities in gate areas
        allGates.forEach { gate ->
            val world = gate.corner1.world
            val entities = world.entities.filterIsInstance<BlockDisplay>()
            entities.forEach { entity ->
                val entityLocation = entity.location
                val minX = minOf(gate.corner1.blockX, gate.corner2.blockX)
                val maxX = maxOf(gate.corner1.blockX, gate.corner2.blockX)
                val minY = minOf(gate.corner1.blockY, gate.corner2.blockY) - 5
                val maxY = maxOf(gate.corner1.blockY, gate.corner2.blockY) + 20
                val minZ = minOf(gate.corner1.blockZ, gate.corner2.blockZ)
                val maxZ = maxOf(gate.corner1.blockZ, gate.corner2.blockZ)
                
                if (entityLocation.blockX in minX..maxX &&
                    entityLocation.blockY in minY..maxY &&
                    entityLocation.blockZ in minZ..maxZ) {
                    entity.remove()
                    totalBlocksRemoved++
                }
            }
        }
        
        plugin.logger.info("Cleanup completed: Removed $totalBlocksRemoved blocks (including $totalBarrierBlocksRemoved barrier blocks)")
         
         // Clear all maps
         activeGates.clear()
         creationSelections.clear()
         creationGateTypes.clear()
         blockToGateMap.clear()
    }


    fun loadGates() {
        val loadedGates = databaseManager.loadAllGates()
        loadedGates.forEach { gate ->
            activeGates[gate.corner1] = gate
            activeGates[gate.corner2] = gate
            
            // Reset animation state on load
            gate.isAnimating = false
            
            // Load linked mechanisms
            gate.linkedMechanisms.addAll(databaseManager.loadGateLinks(gate.id))
            
            // Update block map based on gate type
            when (gate.type) {
                GateType.NORMAL -> updateNormalGateOnLoad(gate)
                GateType.DOUBLE_DOOR -> updateDoubleDoorGateOnLoad(gate)
                GateType.LEVER -> {}
            }
        }
        plugin.logger.info("Loaded ${loadedGates.size} gates from database")
    }
    
    private fun updateNormalGateOnLoad(gate: Gate) {
        // Update block map for normal gates (vertical movement)
        gate.blockData.keys.forEach { location ->
            val moveDistance = if (gate.isOpen) (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble() else 0.0
            val actualLocation = location.clone().add(0.0, moveDistance, 0.0)
            blockToGateMap[actualLocation] = gate
        }
        
        // Force restore gate to exact saved state
        val moveDistance = (abs(gate.corner1.y - gate.corner2.y) + 1).toDouble()
        gate.blockData.forEach { (loc, data) ->
            val openLoc = loc.clone().add(0.0, moveDistance, 0.0)
            val closedLoc = loc
            
            // Always clear both positions regardless of current state
            openLoc.block.type = Material.AIR
            closedLoc.block.type = Material.AIR
            
            // Place blocks in correct position based on saved state
            if (gate.isOpen) {
                openLoc.block.blockData = data
            } else {
                closedLoc.block.blockData = data
            }
        }
    }
    
    private fun updateDoubleDoorGateOnLoad(gate: Gate) {
        // Determine if the gate is aligned along X or Z axis
        val isXAxis = abs(gate.corner1.blockX - gate.corner2.blockX) > abs(gate.corner1.blockZ - gate.corner2.blockZ)
        
        // Calculate the center point of the gate
        val centerX = (gate.corner1.blockX + gate.corner2.blockX) / 2.0
        val centerZ = (gate.corner1.blockZ + gate.corner2.blockZ) / 2.0
        
        // Calculate the maximum distance blocks need to move (half the gate width along the axis)
        // For X-axis gates, blocks move along the X-axis (left/right)
        // For Z-axis gates, blocks move along the Z-axis (forward/backward)
        val moveDistance = if (isXAxis) {
            (abs(gate.corner1.blockX - gate.corner2.blockX) + 1) / 2.0
        } else {
            (abs(gate.corner1.blockZ - gate.corner2.blockZ) + 1) / 2.0
        }
        
        // Update block map for double door gates (horizontal movement)
        gate.blockData.keys.forEach { originalLocation ->
            // Determine which side of the center this block is on
            val side = if (isXAxis) {
                if (originalLocation.x < centerX) -1 else 1
            } else {
                if (originalLocation.z < centerZ) -1 else 1
            }
            
            // Calculate the actual location based on whether the gate is open
            val actualLocation = if (gate.isOpen) {
                if (isXAxis) {
                    originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
                } else {
                    originalLocation.clone().add(0.0, 0.0, side * moveDistance)
                }
            } else {
                originalLocation.clone()
            }
            
            blockToGateMap[actualLocation] = gate
        }
        
        // Force restore gate to exact saved state
        gate.blockData.forEach { (originalLocation, data) ->
            // Determine which side of the center this block is on
            val side = if (isXAxis) {
                if (originalLocation.x < centerX) -1 else 1
            } else {
                if (originalLocation.z < centerZ) -1 else 1
            }
            
            // Calculate open and closed locations
            val openLoc = if (isXAxis) {
                originalLocation.clone().add(side * moveDistance, 0.0, 0.0)
            } else {
                originalLocation.clone().add(0.0, 0.0, side * moveDistance)
            }
            val closedLoc = originalLocation
            
            // Always clear both positions regardless of current state
            openLoc.block.type = Material.AIR
            closedLoc.block.type = Material.AIR
            
            // Place blocks in correct position based on saved state
            if (gate.isOpen) {
                openLoc.block.blockData = data
            } else {
                closedLoc.block.blockData = data
            }
        }
    }


    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.clickedBlock == null) return

        val player = event.player
        val block = event.clickedBlock!!
        
        // Handle lever selection mode
        if (leverSelectionMode.contains(player.uniqueId)) {
            event.isCancelled = true
            
            // First click should be on a lever or button
            if (block.type == Material.LEVER || block.type.name.contains("BUTTON", ignoreCase = true)) {
                // Store the mechanism location temporarily
                player.setMetadata("selectedMechanism", FixedMetadataValue(plugin, block.location))
                val mechanismType = if (block.type == Material.LEVER) "lever" else "button"
                player.sendMessage("§a$mechanismType selected. Now right-click a gate to link them.")
                
                // Show green particles around the selected mechanism
                block.location.world.spawnParticle(
                    Particle.DUST,
                    block.location.clone().add(0.5, 0.5, 0.5),
                    15,
                    0.3, 0.3, 0.3,
                    0.0,
                    Particle.DustOptions(Color.GREEN, 1.0f)
                )
                
                // Create a particle effect that lasts for a moment
                object : BukkitRunnable() {
                    var count = 0
                    override fun run() {
                        if (count >= 20 || !leverSelectionMode.contains(player.uniqueId)) {
                            this.cancel()
                            return
                        }
                        
                        block.location.world.spawnParticle(
                            Particle.DUST,
                            block.location.clone().add(0.5, 0.5, 0.5),
                            5,
                            0.3, 0.3, 0.3,
                            0.0,
                            Particle.DustOptions(Color.GREEN, 1.0f)
                        )
                        count++
                    }
                }.runTaskTimer(plugin, 5L, 5L)
                
                return
            } 
            // Second click should be on a gate
            else if (player.hasMetadata("selectedMechanism")) {
                val mechanismLocation = player.getMetadata("selectedMechanism")[0].value() as Location
                val gate = findGateByBlock(block.location)
                
                if (gate != null) {
                    // Link the mechanism to the gate
                    if (linkGateToMechanism(player, block.location, mechanismLocation)) {
                        // Clear the metadata after successful linking
                        player.removeMetadata("selectedMechanism", plugin)
                        // Exit lever selection mode
                        leverSelectionMode.remove(player.uniqueId)
                        
                        // Show success particles around the gate
                        block.location.world.spawnParticle(
                            Particle.DUST,
                            block.location.clone().add(0.5, 0.5, 0.5),
                            30,
                            0.5, 0.5, 0.5,
                            0.0,
                            Particle.DustOptions(Color.GREEN, 1.0f)
                        )
                        
                        // Also show particles at the mechanism location
                        mechanismLocation.world.spawnParticle(
                            Particle.DUST,
                            mechanismLocation.clone().add(0.5, 0.5, 0.5),
                            30,
                            0.5, 0.5, 0.5,
                            0.0,
                            Particle.DustOptions(Color.GREEN, 1.0f)
                        )
                    }
                } else {
                    player.sendMessage("§cYou must click on a gate block to link the mechanism.")
                }
                return
            }
            return
        }
        
        // Check if the player clicked a lever or button (for normal operation)
        if (block.type == Material.LEVER || block.type.name.contains("BUTTON", ignoreCase = true)) {
            // Check if this mechanism is linked to any gate
            val mechanismLocation = block.location
            val linkedGate = findGateWithLinkedLever(mechanismLocation)
            
            if (linkedGate != null) {
                // Toggle the gate when mechanism is clicked
                if (toggleGateViaLever(player, linkedGate)) {
                    // Don't cancel the event for levers so they can still flip
                    if (block.type.name.contains("BUTTON", ignoreCase = true)) {
                        event.isCancelled = true
                    }
                    return
                }
            }
        }
        
        // Only handle gate creation, no longer toggle gates on right-click
        if (!creationSelections.containsKey(player.uniqueId)) {
            // We no longer toggle gates on direct right-click
            return
        }

        event.isCancelled = true
        val clickedBlock = event.clickedBlock!!
        val firstCorner = creationSelections[player.uniqueId]

        if (firstCorner == null) {
            creationSelections[player.uniqueId] = clickedBlock.location
            player.sendMessage("§aFirst corner set. Now right-click the second corner.")
        } else {
            if (firstCorner.world != clickedBlock.world) {
                player.sendMessage("§cBoth corners must be in the same world.")
                return
            }
            if (firstCorner == clickedBlock.location) {
                player.sendMessage("§cSecond corner cannot be the same as the first.")
                return
            }
            // Get the gate type from the creationGateTypes map or default to NORMAL
            val gateType = creationGateTypes.remove(player.uniqueId) ?: GateType.NORMAL
            createGate(player, firstCorner, clickedBlock.location, gateType)
            creationSelections.remove(player.uniqueId)
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val location = block.location
        
        // Check if the broken block is a lever or button linked to a gate
        if (block.type == Material.LEVER || block.type.name.contains("BUTTON", ignoreCase = true)) {
            val linkedGate = findGateWithLinkedLever(location)
            if (linkedGate != null) {
                // Toggle the gate open when the lever/button is broken
                if (!linkedGate.isOpen) {
                    toggleGateViaLever(event.player, linkedGate)
                }
                
                // Remove the lever/button from the gate's linked mechanisms
                linkedGate.linkedMechanisms.remove(location)
                databaseManager.saveGateLinks(linkedGate.id, linkedGate.linkedMechanisms)
                
                event.player.sendMessage("§aThe mechanism was broken and the gate was opened.")
                return
            }
        }
        
        // Check if the block is part of a gate
        val gate = findGateByBlock(location) ?: return
        
        // Allow breaking if player has permission or if it's their own gate
        val player = event.player
        if (player != null && gate.owner != player.uniqueId && !player.hasPermission("gate.admin")) {
            player.sendMessage("§cYou cannot break blocks from someone else's gate.")
            event.isCancelled = true
            return
        }
        
        // Remove the gate when any of its blocks are broken
        removeGateWhenBroken(gate, player)
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        handleExplosion(event.blockList())
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        handleExplosion(event.blockList())
    }
    
    @EventHandler
    fun onPlayerMove(event: PlayerMoveEvent) {
        // Only process every few ticks to avoid performance issues
        if (event.player.ticksLived % 5 != 0) return
        
        val player = event.player
        
        // Only show particles if player is in lever selection mode
        if (!leverSelectionMode.contains(player.uniqueId)) return
        
        val targetBlock = player.getTargetBlock(null, 5)
        
        // Check if player is looking at a gate block
        val gate = findGateByBlock(targetBlock.location)
        if (gate != null) {
            // Show white particles around the gate block
            targetBlock.location.world.spawnParticle(
                Particle.DUST,
                targetBlock.location.clone().add(0.5, 0.5, 0.5),
                5,
                0.3, 0.3, 0.3,
                0.0,
                Particle.DustOptions(Color.WHITE, 0.7f)
            )
            
            // If player has already selected a mechanism, show a connecting line of particles
            if (player.hasMetadata("selectedMechanism")) {
                val mechanismLocation = player.getMetadata("selectedMechanism")[0].value() as Location
                drawParticleLine(mechanismLocation, targetBlock.location, player)
            }
            return
        }
        
        // Check if player is looking at a lever or button
        if (targetBlock.type == Material.LEVER || targetBlock.type.name.contains("BUTTON", ignoreCase = true)) {
            // Show white particles around the lever/button
            targetBlock.location.world.spawnParticle(
                Particle.DUST,
                targetBlock.location.clone().add(0.5, 0.5, 0.5),
                5,
                0.3, 0.3, 0.3,
                0.0,
                Particle.DustOptions(Color.WHITE, 0.7f)
            )
        }
    }
    
    // Helper method to draw a line of particles between two locations
    private fun drawParticleLine(start: Location, end: Location, player: Player) {
        if (start.world != end.world) return
        
        val distance = start.distance(end)
        val points = max(5, (distance * 2).toInt()) // At least 5 points, more for longer distances
        
        val vector = end.toVector().subtract(start.toVector()).normalize().multiply(distance / points)
        val startVector = start.toVector().clone().add(vector.clone().multiply(0.5)) // Start a bit away from the block
        
        for (i in 0 until points) {
            val point = startVector.clone().add(vector.clone().multiply(i.toDouble()))
            start.world.spawnParticle(
                Particle.DUST,
                point.x, point.y, point.z,
                1,
                0.05, 0.05, 0.05,
                0.0,
                Particle.DustOptions(Color.fromRGB(200, 200, 255), 0.5f)
            )
        }
    }

    private fun handleExplosion(blocks: List<org.bukkit.block.Block>) {
        val gatesToRemove = mutableSetOf<Gate>()
        val mechanismsToProcess = mutableMapOf<Location, Gate>()
        
        blocks.forEach { block ->
            // Check if the block is a lever or button linked to a gate
            if (block.type == Material.LEVER || block.type.name.contains("BUTTON", ignoreCase = true)) {
                val linkedGate = findGateWithLinkedLever(block.location)
                if (linkedGate != null) {
                    mechanismsToProcess[block.location] = linkedGate
                }
            }
            
            // Check if the block is part of a gate
            val gate = findGateByBlock(block.location)
            if (gate != null) {
                gatesToRemove.add(gate)
            }
        }
        
        // Process mechanisms first - open gates and remove the mechanisms
        mechanismsToProcess.forEach { (location, gate) ->
            // Open the gate if it's closed
            if (!gate.isOpen) {
                toggleGateAnimation(gate)
                databaseManager.saveGate(gate)
            }
            
            // Remove the mechanism from the gate's linked mechanisms
            gate.linkedMechanisms.remove(location)
            databaseManager.saveGateLinks(gate.id, gate.linkedMechanisms)
            plugin.logger.info("Mechanism at ${location.blockX}, ${location.blockY}, ${location.blockZ} was destroyed by explosion and removed from gate ${gate.id}")
        }
        
        // Then remove any gates that were directly affected
        gatesToRemove.forEach { gate ->
            removeGateWhenBroken(gate, null)
        }
    }

    private fun removeGateWhenBroken(gate: Gate, player: Player?) {
        // Remove from database
        databaseManager.deleteGate(gate.id)

        // Remove from internal maps
        activeGates.remove(gate.corner1)
        activeGates.remove(gate.corner2)
        blockToGateMap.entries.removeIf { it.value == gate }

        if (player != null) {
            player.sendMessage("§aGate has been broken and is no longer functional.")
        } else {
            plugin.logger.info("Gate ${gate.id} was destroyed and removed from the database.")
        }
    }
}