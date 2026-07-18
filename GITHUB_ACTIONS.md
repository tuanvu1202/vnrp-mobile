# Build APK bằng GitHub Actions

1. Tạo repository GitHub mới.
2. Upload **toàn bộ nội dung trong thư mục project** lên repository. Ở trang gốc repository phải thấy trực tiếp `app`, `.github`, `build.gradle.kts`, `settings.gradle.kts`.
3. Mở tab **Actions** → chọn **Build VNRP Launcher APK** → **Run workflow**.
4. Khi job có dấu tích xanh, mở workflow run và tải artifact **VNRP-Mobile-Launcher-debug**.
5. Giải nén artifact để lấy `app-debug.apk`.

Workflow cũng tự chạy mỗi khi push lên nhánh `main` hoặc `master`.

Đây là APK debug để thử nghiệm, chưa phải APK release dành cho Google Play.
