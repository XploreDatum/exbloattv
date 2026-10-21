package dev.mntl.exbloattv.ui.screens

import androidx.annotation.StringRes
import dev.mntl.exbloattv.R

/** What a step's picture shows: a TV menu with one item picked, an item to press OK on N times, or a switch to turn on. */
sealed interface StepPicture {
    data class Menu(@StringRes val items: List<Int>, val pick: Int) : StepPicture
    data class PressOk(@StringRes val items: List<Int>, val pick: Int, val times: Int = 7) : StepPicture
    data class TurnOn(@StringRes val items: List<Int>, val pick: Int) : StepPicture
}

data class BrandStep(@StringRes val text: Int, val picture: StepPicture)

/** Spec §6.2: the "Turn on debugging" steps for each kind of TV. */
enum class Brand(@StringRes val label: Int, val networkTip: Boolean = true) {
    ANDROID_TV(R.string.brand_android_tv),
    GOOGLE_TV(R.string.brand_google_tv),
    XIAOMI(R.string.brand_xiaomi),
    TCL(R.string.brand_tcl),
    SONY(R.string.brand_sony),
    HISENSE(R.string.brand_hisense),
    PHILIPS(R.string.brand_philips),
    FIRE_TV(R.string.brand_fire_tv, networkTip = false);

    val steps: List<BrandStep> get() = stepsFor(this)

    companion object {
        /** A first guess from the name the TV announces on the network; the person can change it. */
        fun guess(name: String?): Brand {
            val n = name?.lowercase().orEmpty()
            return when {
                "fire" in n || "aft" in n -> FIRE_TV
                "mi box" in n || "mibox" in n || "xiaomi" in n || "mi tv" in n -> XIAOMI
                "tcl" in n -> TCL
                "sony" in n || "bravia" in n -> SONY
                "hisense" in n -> HISENSE
                "philips" in n -> PHILIPS
                "chromecast" in n || "google tv" in n -> GOOGLE_TV
                else -> ANDROID_TV
            }
        }
    }
}

private val ABOUT_LIST = listOf(R.string.pic_model, R.string.pic_version, R.string.pic_build)
private val ABOUT_LIST_GTV = listOf(R.string.pic_model, R.string.pic_version, R.string.pic_tv_os_build)
private val DEV_LIST = listOf(R.string.pic_stay_awake, R.string.pic_usb_debugging)

private fun deviceAbout() = BrandStep(
    R.string.step_atv_1,
    StepPicture.Menu(listOf(R.string.pic_network, R.string.pic_apps, R.string.pic_device_prefs, R.string.pic_about), 3),
)
private fun systemAbout(@StringRes text: Int) = BrandStep(
    text,
    StepPicture.Menu(listOf(R.string.pic_network, R.string.pic_apps, R.string.pic_system, R.string.pic_about), 3),
)
private fun pressBuild(@StringRes text: Int = R.string.step_atv_2) = BrandStep(text, StepPicture.PressOk(ABOUT_LIST, 2))
private fun pressOsBuild(@StringRes text: Int) = BrandStep(text, StepPicture.PressOk(ABOUT_LIST_GTV, 2))
private fun usbDebugging(@StringRes text: Int) = BrandStep(text, StepPicture.TurnOn(DEV_LIST, 1))

private fun stepsFor(b: Brand): List<BrandStep> = when (b) {
    Brand.ANDROID_TV -> listOf(deviceAbout(), pressBuild(), usbDebugging(R.string.step_atv_3))
    Brand.GOOGLE_TV -> listOf(systemAbout(R.string.step_gtv_1), pressOsBuild(R.string.step_gtv_2), usbDebugging(R.string.step_gtv_3))
    Brand.XIAOMI -> listOf(deviceAbout(), pressBuild(), usbDebugging(R.string.step_mi_3))
    Brand.TCL, Brand.SONY -> listOf(systemAbout(R.string.step_newer_1), pressOsBuild(R.string.step_newer_2), usbDebugging(R.string.step_atv_3))
    Brand.HISENSE -> listOf(deviceAbout(), pressBuild(), usbDebugging(R.string.step_mi_3))
    Brand.PHILIPS -> listOf(
        BrandStep(
            R.string.step_philips_1,
            StepPicture.Menu(listOf(R.string.pic_all_settings, R.string.pic_android_settings, R.string.pic_device_prefs, R.string.pic_about), 3),
        ),
        pressBuild(),
        usbDebugging(R.string.step_atv_3),
    )
    Brand.FIRE_TV -> listOf(
        BrandStep(R.string.step_fire_1, StepPicture.Menu(listOf(R.string.pic_network, R.string.pic_apps, R.string.pic_my_fire_tv, R.string.pic_about), 3)),
        BrandStep(R.string.step_fire_2, StepPicture.PressOk(listOf(R.string.pic_fire_stick, R.string.pic_version), 0)),
        BrandStep(R.string.step_fire_3, StepPicture.TurnOn(listOf(R.string.pic_debugging, R.string.pic_apps), 0)),
    )
}
