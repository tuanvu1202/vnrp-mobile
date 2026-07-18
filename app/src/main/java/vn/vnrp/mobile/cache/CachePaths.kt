package vn.vnrp.mobile.cache

import android.content.Context
import vn.vnrp.mobile.LauncherConfig
import java.io.File

object CachePaths {
    fun root(context: Context): File {
        val external = requireNotNull(context.getExternalFilesDir(null)) {
            "Không lấy được thư mục external files của ứng dụng."
        }
        return File(external, LauncherConfig.CACHE_DIRECTORY).apply { mkdirs() }
    }

    fun safeTarget(root: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "Đường dẫn cache bị rỗng." }
        require(!relativePath.startsWith('/')) { "Không chấp nhận đường dẫn tuyệt đối." }

        val target = File(root, relativePath).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) {
            "Đường dẫn cache không an toàn: $relativePath"
        }
        return target
    }
}
