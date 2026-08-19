package llc.redstone.systemsapi.progress

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import llc.redstone.systemsapi.SystemsAPI.LOGGER
import llc.redstone.systemsapi.SystemsAPI.MINECRAFT
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class StatDto(val mean: Double = 0.0, val n: Int = 0)

/** Every map is nullable because Gson bypasses Kotlin constructors and ignores declared defaults. */
internal class CalibrationData(
    val v: Int = CalibrationStore.SCHEMA_VERSION,
    val mc: String? = null,
    val ops: Map<String, StatDto>? = null,
    val timeouts: Map<String, StatDto>? = null,
    val pages: Map<String, StatDto>? = null,
    val cycles: Map<String, StatDto>? = null,
    val skips: Map<String, IntArray>? = null,
    val exportActionMs: StatDto? = null,
    val exportActionOps: StatDto? = null,
)

/**
 * Persists the learned timing model between sessions, so the first import after a launch runs on
 * real measurements rather than cold-start priors.
 *
 * Uses Gson, which ships with Minecraft; the module has no kotlinx.serialization plugin and adding
 * one for a single file is not worth the build change.
 */
internal object CalibrationStore {

    const val SCHEMA_VERSION = 1

    private const val FILE_NAME = "systemsapi-calibration.json"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private val path: Path
        get() = FabricLoader.getInstance().configDir.resolve(FILE_NAME)

    fun load(): CalibrationData? {
        val file = path
        if (!Files.isRegularFile(file)) return null
        return try {
            val data = Files.newBufferedReader(file).use { gson.fromJson(it, CalibrationData::class.java) }
            when {
                data == null -> null
                data.v != SCHEMA_VERSION -> {
                    LOGGER.info("Discarding calibration from schema v{} (expected v{}).", data.v, SCHEMA_VERSION)
                    null
                }
                // Menu layouts differ between versions, so page-turn counts do not transfer.
                data.mc != null && data.mc != MINECRAFT -> {
                    LOGGER.info("Discarding calibration recorded on Minecraft {} (running {}).", data.mc, MINECRAFT)
                    null
                }
                else -> data
            }
        } catch (e: Exception) {
            LOGGER.warn("Failed to read {}, starting from cold-start priors.", FILE_NAME, e)
            null
        }
    }

    /**
     * Written on a daemon thread rather than SystemsAPI's coroutine scope, since `cancelImport`
     * cancels that scope's children and a calibration write must not be collateral damage.
     */
    fun saveAsync(data: CalibrationData) {
        val thread = Thread({ save(data) }, "SystemsAPI-calibration-save")
        thread.isDaemon = true
        thread.start()
    }

    private fun save(data: CalibrationData) {
        try {
            val file = path
            Files.createDirectories(file.parent)
            // Write-then-move, so a crash mid-write cannot leave a truncated file behind.
            val tmp = file.resolveSibling("$FILE_NAME.tmp")
            Files.newBufferedWriter(tmp).use { gson.toJson(data, it) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            LOGGER.warn("Failed to write {}.", FILE_NAME, e)
        }
    }
}
