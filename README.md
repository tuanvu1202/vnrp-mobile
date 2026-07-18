# VNRP Mobile Launcher Starter

Bản khởi đầu để làm launcher SA-MP Mobile với hai chức năng đầu tiên:

1. Tải/kiểm tra cache qua manifest JSON, SHA-256 và file `.part`.
2. Giao diện web kiểu “CEF” trên Android bằng `WebView` + bridge JavaScript.

> Đây chưa phải client SA-MP hoàn chỉnh và chưa chứa file GTA SA, APK game, `libGTASA.so` hay `libsamp.so`.

## Kiến trúc hiện tại

```text
MainActivity
├── WorkManager -> CacheDownloadWorker
│   ├── tải manifest HTTPS
│   ├── kiểm tra size + SHA-256
│   ├── tải vào .part
│   └── đổi tên sau khi xác minh
├── WebUiActivity -> WebViewAssetLoader
│   └── assets/ui/index.html
└── GameLauncher
    └── mở package game đã cấu hình
```

Cache được lưu trong thư mục app-specific:

```text
/storage/emulated/0/Android/data/vn.vnrp.mobile/files/SAMP/
```

Không cần quyền truy cập toàn bộ bộ nhớ trên Android mới.

## 1. Mở project

- Cài Android Studio.
- Mở folder project này.
- Chọn JDK 17.
- Sync Gradle.
- Nếu Android Studio báo thiếu SDK 35 thì cài Android SDK Platform 35.

Project không kèm `gradle-wrapper.jar`; Android Studio có thể tạo/cập nhật Gradle wrapper khi bạn sync. Nếu cần, chạy Gradle wrapper từ Android Studio sau khi project đã sync.

## 2. Cấu hình URL và package game

Sửa trong `app/build.gradle.kts`:

```kotlin
buildConfigField(
    "String",
    "MANIFEST_URL",
    "\"https://cdn.example.com/mobile/manifest.json\""
)

buildConfigField(
    "String",
    "GAME_PACKAGE",
    "\"com.rockstargames.gtasa\""
)
```

`GAME_PACKAGE` sau này phải đổi thành package của client SA-MP Mobile mà bạn build.

## 3. Tạo manifest cache

Đưa cache vào một folder, ví dụ:

```text
cdn/cache/
├── SAMP/settings.ini
├── texdb/...
└── audio/...
```

Chạy:

```bash
python tools/generate_manifest.py \
  --root cdn/cache \
  --base-url https://cdn.tenmiencuaban.com/mobile/cache \
  --version 2026.07.18.1 \
  --output cdn/manifest.json
```

Sau đó upload cả `cache/` và `manifest.json` lên CDN HTTPS.

Schema:

```json
{
  "version": "2026.07.18.1",
  "files": [
    {
      "path": "core/file.dat",
      "url": "https://cdn.example.com/mobile/cache/core/file.dat",
      "size": 1234,
      "sha256": "..."
    }
  ]
}
```

## 4. Test WebView bridge

Nhấn **Test CEF / WebView**.

- Web UI gọi Android bằng `NativeGame.emit(event, payload)`.
- Android gọi JS bằng `window.game.receive(event, payload)`.

Sau này `WebUiActivity` không còn là màn hình riêng. Ta sẽ chuyển `WebView` thành lớp overlay nằm trên `GameActivity`/`NvEventQueueActivity` của client SA-MP.

## 5. Việc tiếp theo

Mốc tiếp theo nên làm theo thứ tự:

1. Build launcher standalone chạy được trên điện thoại thật.
2. Đưa cache thật nhỏ lên CDN và test cập nhật.
3. Chọn source SA-MP Mobile rồi đổi `GAME_PACKAGE` để launcher mở client.
4. Gộp launcher và game vào cùng APK/package hoặc bảo đảm hai app dùng được cùng đường dẫn dữ liệu.
5. Đưa `WebView` overlay vào Activity đang render GTA.
6. Viết JNI bridge `WebView -> Kotlin/Java -> C++ -> RakNet -> Pawn`.

## Lưu ý pháp lý

Không đóng gói hoặc phát tán tài sản GTA San Andreas mà bạn không có quyền phân phối. Starter này chỉ cung cấp updater và WebView bridge, không chứa tài sản game.
