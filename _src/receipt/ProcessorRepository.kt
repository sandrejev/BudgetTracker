package com.example.budgettracker.receipt

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Loads and saves user-defined processor configs from the app's private files directory.
 * Built-in configs are always available but cannot be modified or deleted.
 */
class ProcessorRepository(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "processors").also { it.mkdirs() }

    /** All processors: built-in first, then user-added sorted by name. */
    fun loadAll(): List<ProcessorConfig> {
        val builtin = ProcessorConfig.ALL_BUILTIN
        val user = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { ProcessorConfig.fromJsonString(file.readText()) }.getOrNull()
            }
            ?.sortedBy { it.name }
            ?: emptyList()
        return builtin + user
    }

    fun save(config: ProcessorConfig) {
        require(!config.isBuiltIn) { "Cannot overwrite built-in processor" }
        File(dir, "${config.id}.json").writeText(config.toJsonString())
    }

    fun delete(config: ProcessorConfig) {
        require(!config.isBuiltIn) { "Cannot delete built-in processor" }
        File(dir, "${config.id}.json").delete()
    }

    fun findById(id: String): ProcessorConfig? = loadAll().firstOrNull { it.id == id }

    /** Try to import a processor from a raw JSON string; returns the config or null on error. */
    fun importFromJson(json: String): Result<ProcessorConfig> = runCatching {
        val config = ProcessorConfig.fromJsonString(json)
        save(config)
        config
    }

    /** Export all user-defined processors as a JSON array string. */
    fun exportUserConfigs(): String {
        val user = loadAll().filter { !it.isBuiltIn }
        return org.json.JSONArray(user.map { it.toJson() }).toString(2)
    }
}
