package crow.evilo.plugin.hotmanga

import crow.evilo.model.MediaKind
import crow.evilo.plugin.api.PluginDescriptor
import crow.evilo.plugin.api.PluginJson
import crow.evilo.plugin.api.PluginOperation
import crow.evilo.plugin.api.PluginOperationKind
import crow.evilo.plugin.api.PluginStep
import crow.evilo.plugin.build.releaseDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Exercises generated guest identity on JVM without starting a Wasmline host.
 * The guest's WASI main requires Wasmline env imports unavailable to plain Node tests;
 * portable request/parser fixtures run in the core module's WASI suite instead.
 */
class HotMangaGuestContractTest {
    @Test
    fun generatedSpecDescribesTheCanonicalMangaProvider() {
        val plugin = HotMangaPlugin(releaseDescriptor)
        val described = PluginJson.decodeFromString<PluginDescriptor>(plugin.describe())
        assertEquals(releaseDescriptor, described)
        assertEquals("crow.evilo.source.hotmanga", described.id)
        assertEquals("HotManga", described.name)
        assertEquals(setOf(MediaKind.MANGA), described.kinds)
        assertEquals(listOf("zh"), described.languages)
    }

    @Test
    fun generatedGuestServiceStartsAnonymousProviderSpecificSearch() {
        val plugin = HotMangaPlugin(releaseDescriptor)
        val operation = PluginOperation(PluginOperationKind.SEARCH, MediaKind.MANGA, "example")
        val step = PluginJson.decodeFromString<PluginStep>(plugin.start(PluginJson.encodeToString(operation)))
        val request = assertNotNull(step.request)
        assertEquals("https://mapi.hotmangasg.com/api/v3/search/comic?platform=3&q=example&limit=20&offset=0&free_type=1&_update=true", request.url)
        assertEquals("2024.04.28", request.headers["version"])
        assertEquals("com.manga2020.app", request.headers["X-Requested-With"])
    }
}
