package app.vitune.android

import android.app.LocaleManager
import android.os.LocaleList
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationRegressionTest {
    @Test
    fun repeatedNavigationKeepsLabelsAndSettingsStable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("pt-BR")
        val device = UiDevice.getInstance(instrumentation)
        Configurator.getInstance().setWaitForIdleTimeout(300)
        ActivityScenario.launch(MainActivity::class.java).use {
            fun tab(label: String) {
                val button = requireNotNull(device.wait(Until.findObject(By.desc(label)), 20000)) { "Missing navigation: " + label }
                assertNotNull("Missing navigation: " + label, button)
                button.click()
                device.waitForIdle(300)
                assertTrue("App disappeared after " + label,
                    device.wait(Until.hasObject(By.pkg(context.packageName)), 5000))
            }
            fun capture(name: String) {
                val path = "/data/local/tmp/vitune-navigation/" + name + ".png"
                runCatching {
                    device.executeShellCommand("mkdir -p /data/local/tmp/vitune-navigation")
                    device.executeShellCommand("screencap -p " + path)
                    if ((device.executeShellCommand("stat -c %s " + path).trim().toLongOrNull() ?: 0L) <= 0L)
                        println("OPTIONAL_SCREENSHOT_UNAVAILABLE")
                }.onFailure { println("OPTIONAL_SCREENSHOT_UNAVAILABLE") }
            }
            repeat(6) {
                tab("Configurações")
                val appearance = requireNotNull(device.wait(Until.findObject(By.text("Aparência")), 10000))
                assertNotNull(appearance)
                assertEquals("Repeated Appearance heading", 1, device.findObjects(By.text("Aparência")).size)
                appearance.click()
                device.waitForIdle(300)
                assertEquals(1, device.findObjects(By.text("Aparência")).size)
                assertTrue("Appearance settings blank", device.wait(Until.hasObject(By.text(context.getString(R.string.color_source))), 5000))
                requireNotNull(device.findObject(By.text("Player"))).click()
                assertTrue("Player settings blank", device.wait(Until.hasObject(By.text(context.getString(R.string.persistent_queue))), 5000))
                requireNotNull(device.findObject(By.text("Aparência"))).click()
                assertTrue("Appearance settings failed to return", device.wait(Until.hasObject(By.text(context.getString(R.string.color_source))), 5000))
                tab("Configurações") // reselecting the current tab must be safe
                tab("Downloads")
                tab("Explorar")
                tab("Início")
            }
            tab("Explorar")
            val more = requireNotNull(device.wait(Until.findObject(By.text("Mais")), 30000)) { "Explore data unavailable for click test" }
            more.click()
            assertTrue("Explore item did not open", device.wait(Until.gone(By.desc("Abrir menu lateral")), 10000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("Abrir menu lateral")), 10000))
            assertFalse("Duplicate Explore title", device.hasObject(By.text("Descubra")))
            capture("explore-compact")
            tab("Configurações")
            assertEquals(1, device.findObjects(By.text("Aparência")).size)
            capture("settings-compact")
            val menu = requireNotNull(device.wait(Until.findObject(By.desc("Abrir menu lateral")), 10000))
            assertNotNull(menu)
            menu.click()
            assertTrue(device.wait(Until.hasObject(By.text("Sua biblioteca")), 5000))
            assertFalse("Removed songs entry still visible", device.hasObject(By.text("Músicas")))
            requireNotNull(device.findObject(By.text("Playlists"))).click()
            assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.new_playlist))), 5000))
            assertFalse("Favorites displayed among playlists", device.hasObject(By.text("Favoritos")))
            assertFalse("Offline displayed among playlists", device.hasObject(By.text("Offline")))
            assertFalse("History displayed among playlists", device.hasObject(By.text("Histórico")))
            assertFalse("Top playlist displayed", device.hasObject(By.textContains("Meu top")))
            val handle = requireNotNull(device.wait(Until.findObject(By.desc("Abrir menu lateral")), 5000))
            val bounds = handle.visibleBounds
            device.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX() + 180, bounds.centerY(), 20)
            assertTrue("Drawer did not open by dragging handle", device.wait(Until.hasObject(By.text("Sua biblioteca")), 5000))
            requireNotNull(device.findObject(By.text("Offline"))).click()
            assertTrue("Offline screen did not open", device.wait(Until.hasObject(By.text("Offline")), 5000))
            requireNotNull(device.findObject(By.desc("Abrir menu lateral"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("Sua biblioteca")), 5000))
            capture("library-with-offline")
            device.pressBack()
            assertFalse(device.wait(Until.hasObject(By.text("Sua biblioteca")), 1000))

            device.executeShellCommand("settings put system font_scale 1.5")
            try {
                tab("Downloads")
                tab("Configurações")
                val nav = requireNotNull(device.wait(Until.findObject(By.desc("Configurações")), 10000))
                val labels = device.findObjects(By.text("Configurações"))
                assertTrue(labels.isNotEmpty())
                val label = labels.maxBy { it.visibleBounds.bottom }
                val density = context.resources.displayMetrics.density
                assertTrue("Bottom label wraps to multiple lines", label.visibleBounds.height() <= 24 * density)
                assertTrue("Label extends beyond button", label.visibleBounds.bottom <= nav.visibleBounds.bottom)
                capture("settings-large-font")
            } finally {
                device.executeShellCommand("settings put system font_scale 1.0")
            }
        }
    }
}
