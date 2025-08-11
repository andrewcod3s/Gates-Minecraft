package me.geonations.drawBridge.database

import me.geonations.drawBridge.gate.GateManager
import me.geonations.drawBridge.gate.GateType
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.sql.*
import java.util.*

class DatabaseManager(private val plugin: JavaPlugin) {
    private var connection: Connection? = null
    private val databaseFile: File = File(plugin.dataFolder, "gates.db")

    init {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }
        initializeDatabase()
    }

    private fun initializeDatabase() {
        try {
            Class.forName("org.sqlite.JDBC")
            connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
            
            // Set busy timeout to 5 seconds to prevent SQLITE_BUSY errors
            val timeoutStatement = connection!!.createStatement()
            timeoutStatement.execute("PRAGMA busy_timeout = 5000")
            timeoutStatement.close()
            
            // Enable WAL mode for better concurrency
            val walStatement = connection!!.createStatement()
            walStatement.execute("PRAGMA journal_mode = WAL")
            walStatement.close()
            
            val statement = connection!!.createStatement()
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS gates (
                    id TEXT PRIMARY KEY,
                    owner TEXT NOT NULL,
                    world TEXT NOT NULL,
                    corner1_x INTEGER NOT NULL,
                    corner1_y INTEGER NOT NULL,
                    corner1_z INTEGER NOT NULL,
                    corner2_x INTEGER NOT NULL,
                    corner2_y INTEGER NOT NULL,
                    corner2_z INTEGER NOT NULL,
                    gate_type TEXT NOT NULL DEFAULT 'NORMAL',
                    sliding_direction TEXT,
                    is_open BOOLEAN NOT NULL DEFAULT 0,
                    integrity_hash TEXT NOT NULL
                )
""")
            
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS gate_blocks (
                    gate_id TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    block_data TEXT NOT NULL,
                    PRIMARY KEY (gate_id, x, y, z),
                    FOREIGN KEY (gate_id) REFERENCES gates(id) ON DELETE CASCADE
                )
            """)
            
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS gate_links (
                    gate_id TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    link_type TEXT NOT NULL DEFAULT 'LEVER',
                    PRIMARY KEY (gate_id, x, y, z),
                    FOREIGN KEY (gate_id) REFERENCES gates(id) ON DELETE CASCADE
                )
            """)
            
            statement.close()
        } catch (e: Exception) {
            plugin.logger.severe("Failed to initialize database: ${e.message}")
        }
    }

    fun saveGate(gate: GateManager.Gate) {
        var insertGate: PreparedStatement? = null
        var deleteBlocks: PreparedStatement? = null
        var insertBlock: PreparedStatement? = null
        
        try {
            val connection = connection ?: return
            connection.autoCommit = false
            
            try {
                val integrityHash = calculateIntegrityHash(gate)
                
                insertGate = connection.prepareStatement(
                    """
                    INSERT OR REPLACE INTO gates (id, owner, world, corner1_x, corner1_y, corner1_z, corner2_x, corner2_y, corner2_z, gate_type, sliding_direction, is_open, integrity_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
                )
                
                insertGate.setString(1, gate.id.toString())
                insertGate.setString(2, gate.owner.toString())
                insertGate.setString(3, gate.corner1.world.name)
                insertGate.setInt(4, gate.corner1.blockX)
                insertGate.setInt(5, gate.corner1.blockY)
                insertGate.setInt(6, gate.corner1.blockZ)
                insertGate.setInt(7, gate.corner2.blockX)
                insertGate.setInt(8, gate.corner2.blockY)
                insertGate.setInt(9, gate.corner2.blockZ)
                insertGate.setString(10, gate.type.name)
                insertGate.setString(11, null)
                insertGate.setBoolean(12, gate.isOpen)
                insertGate.setString(13, integrityHash)
                insertGate.executeUpdate()
                
                // Save blocks
                deleteBlocks = connection.prepareStatement("DELETE FROM gate_blocks WHERE gate_id = ?")
                deleteBlocks.setString(1, gate.id.toString())
                deleteBlocks.executeUpdate()
                
                insertBlock = connection.prepareStatement(
                    """
                    INSERT INTO gate_blocks (gate_id, x, y, z, block_data)
                    VALUES (?, ?, ?, ?, ?)
                    """
                )
                
                gate.blockData.forEach { (location, blockData) ->
                    insertBlock.setString(1, gate.id.toString())
                    insertBlock.setInt(2, location.blockX)
                    insertBlock.setInt(3, location.blockY)
                    insertBlock.setInt(4, location.blockZ)
                    insertBlock.setString(5, blockData.asString)
                    insertBlock.addBatch()
                }
                insertBlock.executeBatch()
                
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                plugin.logger.severe("Failed to save gate ${gate.id}: ${e.message}")
            } finally {
                connection.autoCommit = true
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to save gate ${gate.id}: ${e.message}")
        } finally {
            insertGate?.close()
            deleteBlocks?.close()
            insertBlock?.close()
        }
    }

    fun loadAllGates(): List<GateManager.Gate> {
        val gates = mutableListOf<GateManager.Gate>()
        
        var statement: Statement? = null
        var gateResult: ResultSet? = null
        var blockStatement: PreparedStatement? = null
        var blockResult: ResultSet? = null
        
        try {
            statement = connection!!.createStatement()
            gateResult = statement.executeQuery("SELECT * FROM gates")
            
            while (gateResult.next()) {
                val gateId = UUID.fromString(gateResult.getString("id"))
                val owner = UUID.fromString(gateResult.getString("owner"))
                val worldName = gateResult.getString("world")
                val world = Bukkit.getWorld(worldName) ?: continue
                
                val corner1 = Location(world, 
                    gateResult.getInt("corner1_x").toDouble(), 
                    gateResult.getInt("corner1_y").toDouble(), 
                    gateResult.getInt("corner1_z").toDouble())
                
                val corner2 = Location(world, 
                    gateResult.getInt("corner2_x").toDouble(), 
                    gateResult.getInt("corner2_y").toDouble(), 
                    gateResult.getInt("corner2_z").toDouble())
                
                val gateType = gateResult.getString("gate_type")?.let { typeStr: String -> 
                    try { 
                        GateType.valueOf(typeStr) 
                    } catch (e: IllegalArgumentException) { 
                        GateType.NORMAL 
                    } 
                } ?: GateType.NORMAL
                
                val isOpen = gateResult.getBoolean("is_open")
                val integrityHash = gateResult.getString("integrity_hash")
                
                // Load blocks
                val blocks = mutableMapOf<Location, BlockData>()
                blockStatement = connection!!.prepareStatement("SELECT * FROM gate_blocks WHERE gate_id = ?")
                blockStatement.setString(1, gateId.toString())
                blockResult = blockStatement.executeQuery()
                
                while (blockResult.next()) {
                    val x = blockResult.getInt("x")
                    val y = blockResult.getInt("y")
                    val z = blockResult.getInt("z")
                    val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
                    val blockData = Bukkit.createBlockData(blockResult.getString("block_data"))
                    blocks[location] = blockData
                }
                blockResult?.close()
                blockStatement?.close()
                
                val gate = GateManager.Gate(gateId, owner, corner1, corner2, blocks, gateType, isOpen, false)
                
                // Verify integrity
                if (verifyGateIntegrity(gate, integrityHash)) {
                    gates.add(gate)
                } else {
                    plugin.logger.warning("Gate $gateId failed integrity check, skipping...")
                }
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load gates: ${e.message}")
        } finally {
            gateResult?.close()
            statement?.close()
            blockResult?.close()
            blockStatement?.close()
        }
        
        return gates
    }

    fun deleteGate(gateId: UUID) {
        var statement: PreparedStatement? = null
        try {
            statement = connection!!.prepareStatement("DELETE FROM gates WHERE id = ?")
            statement.setString(1, gateId.toString())
            statement.executeUpdate()
        } catch (e: Exception) {
            plugin.logger.severe("Failed to delete gate $gateId: ${e.message}")
        } finally {
            statement?.close()
        }
    }

    private fun calculateIntegrityHash(gate: GateManager.Gate): String {
        try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            
            // Sort blocks by position for consistent ordering
            val sortedBlocks = gate.blockData.entries.sortedWith(compareBy(
                { it.key.blockX },
                { it.key.blockY },
                { it.key.blockZ }
            ))
            
            // Build consistent string representation
            val builder = StringBuilder()
            
            // Add gate metadata
            builder.append(gate.id.toString()).append("|")
            builder.append(gate.owner.toString()).append("|")
            builder.append(gate.type.name).append("|")
            builder.append(gate.corner1.world.name).append("|")
            builder.append(gate.corner1.blockX).append("|").append(gate.corner1.blockY).append("|").append(gate.corner1.blockZ).append("|")
            builder.append(gate.corner2.blockX).append("|").append(gate.corner2.blockY).append("|").append(gate.corner2.blockZ).append("|")
            builder.append(gate.isOpen).append("|")
            
            // Add block data
            sortedBlocks.forEach { (location, blockData) ->
                builder.append(location.blockX).append(",").append(location.blockY).append(",").append(location.blockZ)
                    .append(":").append(blockData.asString).append("|")
            }
            
            val hashBytes = md.digest(builder.toString().toByteArray())
            return hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to calculate integrity hash: ${e.message}")
            return ""
        }
    }

    private fun verifyGateIntegrity(gate: GateManager.Gate, expectedHash: String): Boolean {
        if (expectedHash.isEmpty()) return true // Allow empty hashes for compatibility
        val currentHash = calculateIntegrityHash(gate)
        return currentHash == expectedHash
    }

    fun checkForIntegrityViolations(gate: GateManager.Gate): Boolean {
        var statement: PreparedStatement? = null
        var result: ResultSet? = null
        try {
            statement = connection!!.prepareStatement("SELECT integrity_hash FROM gates WHERE id = ?")
            statement.setString(1, gate.id.toString())
            result = statement.executeQuery()
            
            if (result.next()) {
                val expectedHash = result.getString("integrity_hash")
                return !verifyGateIntegrity(gate, expectedHash)
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to check integrity for gate ${gate.id}: ${e.message}")
        } finally {
            result?.close()
            statement?.close()
        }
        return true
    }

    fun saveGateLinks(gateId: UUID, links: Set<Location>) {
        var deleteLinks: PreparedStatement? = null
        var insertLink: PreparedStatement? = null
        
        try {
            val connection = connection ?: return
            connection.autoCommit = false
            
            try {
                deleteLinks = connection.prepareStatement("DELETE FROM gate_links WHERE gate_id = ?")
                deleteLinks.setString(1, gateId.toString())
                deleteLinks.executeUpdate()
                
                insertLink = connection.prepareStatement(
                    """
                    INSERT INTO gate_links (gate_id, world, x, y, z, link_type)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """
                )
                
                links.forEach { location ->
                    val block = location.block
                    val linkType = when (block.type) {
                        Material.LEVER -> "LEVER"
                        Material.STONE_BUTTON, Material.OAK_BUTTON, Material.SPRUCE_BUTTON, 
                        Material.BIRCH_BUTTON, Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON, 
                        Material.DARK_OAK_BUTTON, Material.MANGROVE_BUTTON, Material.CHERRY_BUTTON, 
                        Material.BAMBOO_BUTTON, Material.CRIMSON_BUTTON, Material.WARPED_BUTTON, 
                        Material.POLISHED_BLACKSTONE_BUTTON -> "BUTTON"
                        else -> "REDSTONE"
                    }
                    
                    insertLink.setString(1, gateId.toString())
                    insertLink.setString(2, location.world.name)
                    insertLink.setInt(3, location.blockX)
                    insertLink.setInt(4, location.blockY)
                    insertLink.setInt(5, location.blockZ)
                    insertLink.setString(6, linkType)
                    insertLink.addBatch()
                }
                insertLink.executeBatch()
                
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                plugin.logger.severe("Failed to save gate links for $gateId: ${e.message}")
            } finally {
                connection.autoCommit = true
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to save gate links for $gateId: ${e.message}")
        } finally {
            deleteLinks?.close()
            insertLink?.close()
        }
    }
    
    fun loadGateLinks(gateId: UUID): Set<Location> {
        val links = mutableSetOf<Location>()
        var statement: PreparedStatement? = null
        var result: ResultSet? = null
        
        try {
            statement = connection!!.prepareStatement(
                "SELECT world, x, y, z FROM gate_links WHERE gate_id = ?"
            )
            statement.setString(1, gateId.toString())
            result = statement.executeQuery()
            
            while (result.next()) {
                val worldName = result.getString("world")
                val world = Bukkit.getWorld(worldName) ?: continue
                val x = result.getInt("x")
                val y = result.getInt("y")
                val z = result.getInt("z")
                links.add(Location(world, x.toDouble(), y.toDouble(), z.toDouble()))
            }
        } catch (e: Exception) {
            plugin.logger.severe("Failed to load gate links for $gateId: ${e.message}")
        } finally {
            statement?.close()
            result?.close()
        }
        
        return links
    }

    fun close() {
        try {
            connection?.close()
            connection = null
        } catch (e: Exception) {
            plugin.logger.severe("Failed to close database connection: ${e.message}")
        }
    }
    
    private fun <T> withConnection(action: (Connection) -> T): T? {
        return try {
            val conn = connection ?: return null
            synchronized(this) {
                action(conn)
            }
        } catch (e: Exception) {
            plugin.logger.severe("Database operation failed: ${e.message}")
            null
        }
    }
}