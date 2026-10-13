# liKePic 拼图工坊

liKePic 是一款原生 Android 图片拼接与裁剪应用，可将多张照片拼成连续长图。

## 功能

- 从应用内相册选择 2–12 张照片，并按选择顺序拼接
- 横向或纵向拼接，可拖动图片和拼接边界调整显示区域
- 裁剪最终画面，支持撤销和重置
- 将拼接结果保存到系统相册 `Pictures/liKePic拼图工坊`
- 在“关于”页面查看版本信息并检查更新

## 构建

需要 JDK 17 和 Android SDK（API 35）。项目包含 Gradle Wrapper（Gradle 8.10.2）。

Windows：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```sh
./gradlew assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 网络与照片

- 照片在设备本地读取、拼接和保存；应用不会上传照片文件。
- 打开“关于”时会请求 `https://keji.likeyuns.cn/likePic` 获取版本信息。
- 应用启动时会请求 `https://keji.likeyuns.cn/likePic/open.php` 记录访问。服务端可能根据请求记录访问统计及请求元数据。

## 许可

本项目采用 MIT License，详见 [LICENSE](LICENSE)。
