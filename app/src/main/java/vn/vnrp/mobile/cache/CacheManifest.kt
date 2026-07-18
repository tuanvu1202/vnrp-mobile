package vn.vnrp.mobile.cache

import org.json.JSONObject

data class CacheFile(
    val path: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

data class CacheManifest(
    val version: String,
    val files: List<CacheFile>,
) {
    companion object {
        fun parse(rawJson: String): CacheManifest {
            val root = JSONObject(rawJson)
            val array = root.getJSONArray("files")
            val files = buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        CacheFile(
                            path = item.getString("path"),
                            url = item.getString("url"),
                            size = item.getLong("size"),
                            sha256 = item.getString("sha256").lowercase(),
                        )
                    )
                }
            }
            return CacheManifest(
                version = root.getString("version"),
                files = files,
            )
        }
    }
}
