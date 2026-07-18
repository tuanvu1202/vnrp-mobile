package vn.vnrp.mobile

object LauncherConfig {
    const val CACHE_DIRECTORY = "SAMP"
    const val UNIQUE_WORK_NAME = "vnrp-cache-update"

    val manifestUrl: String
        get() = BuildConfig.MANIFEST_URL

    val gamePackage: String
        get() = BuildConfig.GAME_PACKAGE
}
