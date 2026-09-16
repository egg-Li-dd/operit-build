package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.data.model.FunctionType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

/**
 * 旧键(function_config_mapping) -> 候选池的一次性迁移语义。
 *
 * parseLegacyMapping / migrateFromLegacy 是私有实现，这里用反射直达，避免为可测性改动已冻结的生产结构。
 * 构造仅持有 Context 引用，故 mock 一个 Context 即可，不触碰 DataStore。
 */
class LegacyRouteMappingMigrationTest {

    private val manager = FunctionalConfigManager(Mockito.mock(Context::class.java))

    @Suppress("UNCHECKED_CAST")
    private fun parseLegacy(raw: String?): Map<FunctionType, FunctionConfigMapping> {
        val method =
                FunctionalConfigManager::class.java.getDeclaredMethod(
                        "parseLegacyMapping",
                        String::class.java
                )
        method.isAccessible = true
        return method.invoke(manager, raw) as Map<FunctionType, FunctionConfigMapping>
    }

    @Suppress("UNCHECKED_CAST")
    private fun migrateLegacy(raw: String?): Map<FunctionType, FunctionConfigPool> {
        val method =
                FunctionalConfigManager::class.java.getDeclaredMethod(
                        "migrateFromLegacy",
                        String::class.java
                )
        method.isAccessible = true
        return method.invoke(manager, raw) as Map<FunctionType, FunctionConfigPool>
    }

    @Test
    fun legacyObjectForm_migratesToSingleFixedCandidateKeepingModelIndex() {
        val pools = migrateLegacy("{\"CHAT\":{\"configId\":\"c9\",\"modelIndex\":2}}")
        val chat = pools.getValue(FunctionType.CHAT)
        assertEquals(RouteStrategy.FIXED, chat.strategy)
        assertEquals(listOf(FunctionRouteCandidate("c9", 2)), chat.candidates)
    }

    @Test
    fun legacyStringForm_parsesWithZeroModelIndex() {
        val mapping = parseLegacy("{\"SUMMARY\":\"c7\"}")
        assertEquals(FunctionConfigMapping("c7", 0), mapping.getValue(FunctionType.SUMMARY))
    }

    @Test
    fun blankLegacyJson_yieldsDefaultMappingForEveryFunction() {
        val mapping = parseLegacy(null)
        assertEquals(FunctionType.values().size, mapping.size)
        FunctionType.values().forEach { type ->
            assertEquals(
                    FunctionConfigMapping(FunctionalConfigManager.DEFAULT_CONFIG_ID, 0),
                    mapping.getValue(type)
            )
        }
    }
}
