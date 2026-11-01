package com.tufein.valkyr.ui.secondscreen

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.tufein.valkyr.data.service.DownloadService
import com.tufein.valkyr.data.service.GameMetadataService
import com.tufein.valkyr.util.DualScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The second-screen hub as an activity of its own, started on the other display with
 * `ActivityOptions.setLaunchDisplayId` ([SecondScreenLauncher]). That is the "Valkyr runs on the
 * second screen" case: the main window stays where it is and this one shows the hub (continue
 * playing, recent games, downloads, battery and heat). It shows the same content as the
 * presentation of [SecondScreenPresenter]; the presenter does not draw over it because it skips the
 * display the activity is on.
 *
 * Not tried on a device: that the system accepts the launch on the lower screen of a dual-screen
 * handheld. [SecondScreenLauncher.launch] reports false when it does not.
 */
@AndroidEntryPoint
class SecondScreenActivity : AppCompatActivity() {
    @Inject lateinit var metadataService: GameMetadataService
    @Inject lateinit var downloadService: DownloadService
    private var registeredDisplay: Int? = null

    override fun onStart() {
        super.onStart()
        if (registeredDisplay == null) {
            val id = display?.displayId ?: Display.DEFAULT_DISPLAY
            registeredDisplay = id
            DualScreen.HubDisplays.add(id)
        }
    }

    override fun onStop() { unregisterHub(); super.onStop() }
    override fun onDestroy() { unregisterHub(); super.onDestroy() }

    private fun unregisterHub() {
        registeredDisplay?.let { DualScreen.HubDisplays.remove(it) }
        registeredDisplay = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecondScreenRoot(downloadService.downloads) { item ->
                metadataService.lookup(item.file.name, item.file.consoleId, item.file.fileName)
            }
        }
    }
}

object SecondScreenLauncher {

    /** The id of the display the hub can be started on from [context]'s display, or null (single screen). */
    fun targetDisplayId(context: Context): Int? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val own = context.displayIdCompat()
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { DualScreen.DisplayInfo(it.displayId, it.isValid, true) }
        return DualScreen.launchTarget(displays, own)
    }

    /** Starts the hub on the other display; false when there is none or the system refuses. */
    fun launch(context: Context): Boolean {
        val id = targetDisplayId(context) ?: return false
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(id)
        val intent = Intent(context, SecondScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent, options.toBundle()) }.isSuccess
    }
}
