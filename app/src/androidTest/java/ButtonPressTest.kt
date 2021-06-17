import android.content.Intent
import android.net.LocalSocketAddress
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.test.suitebuilder.annotation.SmallTest
import app.adbuster.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import app.adbuster.R
import org.junit.Assert

@RunWith(AndroidJUnit4::class)
@SmallTest
class ButtonPressTest {

    @Test
    @Throws(Exception::class)
    fun ensureVpnStartsUp() {
        val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        dev.pressHome()

        // Wait for launcher
        val launcherPackage = dev.launcherPackageName
        Assert.assertNotNull(launcherPackage)
        dev.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)),
                1000)

        // Launch the app
        val context = InstrumentationRegistry.getContext()
        val intent = context.packageManager.getLaunchIntentForPackage("app.adbuster")
        // Clear out any previous instances
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)

        // Wait for the app to appear
        dev.wait(Until.hasObject(By.pkg("app.adbuster").depth(0)),
                1000)

        val status = dev.findObject(UiSelector().descriptionContains("Vpn Status"))
        val btn = dev.findObject(UiSelector().descriptionContains("Toggle"))

        Assert.assertEquals(status.text, "Not running")

        btn.click()

        Assert.assertEquals(status.text, "Starting Ad Buster")

        val res = dev.wait(Until.hasObject(By.pkg("com.android.vpndialogs").clickable(true).text("OK")), 1000)
        if (res) {
            val ok_vpn = dev.findObject(UiSelector().packageName("com.android.vpndialogs").clickable(true).text("OK"))
            ok_vpn.click()
            SystemClock.sleep(500)
        }

        Assert.assertEquals(status.text, "I ain't 'fraid of no ads!")
    }
}