package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// 为功能配置创建专用的DataStore
private val Context.functionalConfigDataStore: DataStore<Preferences> by
        preferencesDataStore(name = "functional_configs")

/** 功能配置映射数据，包含配置ID和模型索引 */
@Serializable
data class FunctionConfigMapping(
    val configId: String = FunctionalConfigManager.DEFAULT_CONFIG_ID,
    val modelIndex: Int = 0
)

/** 选路策略。仅决定"这一次用哪个候选"，不涉及失败改投。 */
@Serializable
enum class RouteStrategy {
    /** 固定取首个启用候选。 */
    FIXED,
    /** 按启用候选顺序轮询。 */
    ROUND_ROBIN,
    /** 按权重轮询。 */
    WEIGHTED
}

/** 单个候选落点：一个 (configId, modelIndex) 点，叠加启用位与权重。 */
@Serializable
data class FunctionRouteCandidate(
    val configId: String = FunctionalConfigManager.DEFAULT_CONFIG_ID,
    val modelIndex: Int = 0,
    val enabled: Boolean = true,
    val weight: Int = 1
) {
    fun toMapping() = FunctionConfigMapping(configId, modelIndex)
}

/** 功能候选池：有序候选列表 + 显式选路策略。 */
@Serializable
data class FunctionConfigPool(
    val candidates: List<FunctionRouteCandidate> = listOf(FunctionRouteCandidate()),
    val strategy: RouteStrategy = RouteStrategy.FIXED
) {
    /** 主候选：首个启用项；无启用项时退化为首项；空池给默认项。仅用于展示与兼容访问，不参与轮询。 */
    fun primaryCandidate(): FunctionRouteCandidate =
            candidates.firstOrNull { it.enabled } ?: candidates.firstOrNull() ?: FunctionRouteCandidate()

    /** 主候选的映射视图。 */
    fun primaryMapping(): FunctionConfigMapping = primaryCandidate().toMapping()
}

/** 管理不同功能使用的模型配置 这个类用于将FunctionType映射到对应的ModelConfigID */
class FunctionalConfigManager(private val context: Context) {

    // 定义key
    companion object {
        // 功能配置映射key（旧键：仅作一次性迁移来源，不再写入）
        val FUNCTION_CONFIG_MAPPING = stringPreferencesKey("function_config_mapping")

        // 候选池key（新键：唯一可信源）
        val FUNCTION_ROUTE_POOL = stringPreferencesKey("function_route_pool")

        // 默认映射值
        const val DEFAULT_CONFIG_ID = "default"

        private const val TAG = "FunctionalConfigManager"
    }

    // Json解析器
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 获取ModelConfigManager实例用于配置查询
    private val modelConfigManager = ModelConfigManager(context)

    // 获取功能候选池（唯一可信源）
    // 注意：必须声明在依赖它的 flow 之前，Kotlin 属性按声明顺序初始化。
    val functionRoutePoolFlow: Flow<Map<FunctionType, FunctionConfigPool>> =
            context.functionalConfigDataStore.data.map { preferences ->
                val poolJson = preferences[FUNCTION_ROUTE_POOL]
                if (poolJson != null) {
                    try {
                        val rawMap =
                                json.decodeFromString<Map<String, FunctionConfigPool>>(poolJson)
                        rawMap.entries.associate { FunctionType.valueOf(it.key) to it.value }
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "候选池解析失败，回落默认池", e)
                        defaultPools()
                    }
                } else {
                    // 新键缺失：从旧键一次性迁移
                    migrateFromLegacy(preferences[FUNCTION_CONFIG_MAPPING])
                }
            }

    // 获取功能配置映射（保持向后兼容，值为主候选的configId）
    val functionConfigMappingFlow: Flow<Map<FunctionType, String>> =
            functionRoutePoolFlow.map { pools ->
                pools.mapValues { it.value.primaryMapping().configId }
            }

    // 获取完整的功能配置映射（包含modelIndex，值为主候选）
    val functionConfigMappingWithIndexFlow: Flow<Map<FunctionType, FunctionConfigMapping>> =
            functionRoutePoolFlow.map { pools ->
                pools.mapValues { it.value.primaryMapping() }
            }

    /** 旧键 -> 候选池（单候选、FIXED）。 */
    private fun migrateFromLegacy(mappingJson: String?): Map<FunctionType, FunctionConfigPool> {
        val legacyMapping = parseLegacyMapping(mappingJson)
        return legacyMapping.mapValues { (_, mapping) ->
            FunctionConfigPool(
                    candidates = listOf(FunctionRouteCandidate(mapping.configId, mapping.modelIndex)),
                    strategy = RouteStrategy.FIXED
            )
        }
    }

    /** 解析旧键 JSON：兼容"含 modelIndex 对象"与"仅 configId 字符串"两种历史形态。 */
    private fun parseLegacyMapping(mappingJson: String?): Map<FunctionType, FunctionConfigMapping> {
        val raw = mappingJson ?: "{}"
        if (raw.isBlank() || raw == "{}") {
            return defaultMappings()
        }
        return try {
            val rawMap = json.decodeFromString<Map<String, FunctionConfigMapping>>(raw)
            rawMap.entries.associate { FunctionType.valueOf(it.key) to it.value }
        } catch (e: Exception) {
            try {
                val rawMap = json.decodeFromString<Map<String, String>>(raw)
                rawMap.entries.associate {
                    FunctionType.valueOf(it.key) to FunctionConfigMapping(it.value, 0)
                }
            } catch (e2: Exception) {
                AppLogger.w(TAG, "旧功能映射解析失败，回落默认映射", e2)
                defaultMappings()
            }
        }
    }

    private fun defaultMappings(): Map<FunctionType, FunctionConfigMapping> =
            FunctionType.values().associateWith { FunctionConfigMapping(DEFAULT_CONFIG_ID, 0) }

    private fun defaultPools(): Map<FunctionType, FunctionConfigPool> =
            FunctionType.values().associateWith { FunctionConfigPool() }

    // 初始化，确保有默认映射
    suspend fun initializeIfNeeded() {
        val pools = functionRoutePoolFlow.first()

        // 只在候选池真正为空时才创建默认池，避免覆盖用户已保存的候选
        if (pools.isEmpty()) {
            saveRoutePool(defaultPools())
        }

        // 确保ModelConfigManager也已初始化
        modelConfigManager.initializeIfNeeded()
    }

    // 保存功能配置映射（保持向后兼容），仅改写各功能主候选
    suspend fun saveFunctionConfigMapping(mapping: Map<FunctionType, String>) {
        val mappingWithIndex = mapping.entries.associate { it.key to FunctionConfigMapping(it.value, 0) }
        saveFunctionConfigMappingWithIndex(mappingWithIndex)
    }

    // 保存功能配置映射（包含modelIndex），仅改写各功能主候选，保留其余候选与策略
    suspend fun saveFunctionConfigMappingWithIndex(mapping: Map<FunctionType, FunctionConfigMapping>) {
        val current = functionRoutePoolFlow.first().toMutableMap()
        mapping.forEach { (functionType, selected) ->
            val pool = current[functionType] ?: FunctionConfigPool()
            val candidates = pool.candidates.toMutableList()
            if (candidates.isEmpty()) {
                candidates.add(FunctionRouteCandidate(selected.configId, selected.modelIndex))
            } else {
                candidates[0] =
                        candidates[0].copy(configId = selected.configId, modelIndex = selected.modelIndex)
            }
            current[functionType] = pool.copy(candidates = candidates)
        }
        saveRoutePool(current)
    }

    // 保存候选池（唯一写入路径）
    suspend fun saveRoutePool(mapping: Map<FunctionType, FunctionConfigPool>) {
        val stringMapping = mapping.entries.associate { it.key.name to it.value }
        context.functionalConfigDataStore.edit { preferences ->
            preferences[FUNCTION_ROUTE_POOL] = json.encodeToString(stringMapping)
        }
    }

    // 保存单个功能的候选池
    suspend fun saveRoutePool(functionType: FunctionType, pool: FunctionConfigPool) {
        val current = functionRoutePoolFlow.first().toMutableMap()
        current[functionType] = pool
        saveRoutePool(current)
    }

    // 获取指定功能的候选池
    suspend fun getRoutePool(functionType: FunctionType): FunctionConfigPool {
        val pools = functionRoutePoolFlow.first()
        return pools[functionType] ?: FunctionConfigPool()
    }

    // 获取指定功能的主候选（展示用，不参与轮询）
    suspend fun getEffectiveCandidate(functionType: FunctionType): FunctionRouteCandidate {
        return getRoutePool(functionType).primaryCandidate()
    }

    // 获取指定功能的配置ID
    suspend fun getConfigIdForFunction(functionType: FunctionType): String {
        val mapping = functionConfigMappingFlow.first()
        return mapping[functionType] ?: DEFAULT_CONFIG_ID
    }

    // 获取指定功能的完整配置（包含modelIndex，主候选）
    suspend fun getConfigMappingForFunction(functionType: FunctionType): FunctionConfigMapping {
        val mapping = functionConfigMappingWithIndexFlow.first()
        return mapping[functionType] ?: FunctionConfigMapping(DEFAULT_CONFIG_ID, 0)
    }

    // 设置指定功能的配置ID（改写主候选）
    suspend fun setConfigForFunction(functionType: FunctionType, configId: String) {
        setConfigForFunction(functionType, configId, 0)
    }

    // 设置指定功能的配置ID和模型索引（改写主候选，保留其余候选与策略）
    suspend fun setConfigForFunction(functionType: FunctionType, configId: String, modelIndex: Int) {
        val pools = functionRoutePoolFlow.first().toMutableMap()
        val pool = pools[functionType] ?: FunctionConfigPool()
        val candidates = pool.candidates.toMutableList()
        if (candidates.isEmpty()) {
            candidates.add(FunctionRouteCandidate(configId, modelIndex))
        } else {
            candidates[0] = candidates[0].copy(configId = configId, modelIndex = modelIndex)
        }
        pools[functionType] = pool.copy(candidates = candidates)
        saveRoutePool(pools)
    }

    // 重置指定功能的配置为默认
    suspend fun resetFunctionConfig(functionType: FunctionType) {
        setConfigForFunction(functionType, DEFAULT_CONFIG_ID)
    }

    // 重置所有功能配置为默认（重建为单候选默认池）
    suspend fun resetAllFunctionConfigs() {
        saveRoutePool(defaultPools())
    }
}