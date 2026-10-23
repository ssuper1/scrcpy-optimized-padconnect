# padconnect

在 Android 手机上控制 Android 平板，基于 scrcpy-android 修改的个人版本。当前版本：**0.6.8-phone**。

支持无线调试配对、原始画面投屏，以及按手机比例创建的独立屏幕；提供应用选择、界面大小调整、横竖屏切换和可拖动的侧边菜单。控制手机进入沉浸式全屏，远程独立屏幕的系统栏由平板系统决定是否提供。

应用只需安装在控制端。被控端需要开启开发者选项和无线调试，并与控制端完成配对。部分系统、应用可能限制独立屏幕；出现兼容性问题时可切换为原始比例模式。

## Android Studio

打开本仓库根目录，等待 Gradle 同步完成，选择运行配置 **scrcpy 手机模式**（模块 `remote`）。不要选择根目录原有的 `app` 示例模块。

- JDK 21（Gradle 工具链配置可以自动下载）。
- Android SDK Platform 36、Build Tools 36.1.0。
- `local.properties` 配置自己的 `sdk.dir`，或设置 `ANDROID_HOME`。
- 首次构建需要网络下载 Gradle 和 Maven/JitPack 依赖。

开发版包名为 `invalid.lena.scrfix.debug`，可与发布版 `invalid.lena.scrfix` 共存；开发版需要单独配对。

## 构建和检查

Windows PowerShell：

```powershell
.\gradlew.bat :remote:assembleDebug :remote:testDebugUnitTest :adb:testDebugUnitTest :remote:lintDebug --console=plain --no-configuration-cache
```

APK 位于 `scrcpy-android/app/build/outputs/apk/debug/`。

发布构建：

```powershell
.\gradlew.bat :remote:assembleRelease :remote:lintRelease --console=plain --no-configuration-cache
```

发布签名通过环境变量 `KEYSTORE_PATH`、`KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASS` 配置；四项齐全时 Gradle 会签名，否则生成未签名的发布包。仓库不包含签名密钥；重新生成密钥签名的包不能直接覆盖以前使用另一把密钥签名的安装包。

`scripts/build-phone.ps1` 保留原维护者本地测试签名流程，默认从用户目录的 `.codex/app-signing/padconnect-repair.p12` 读取密钥；一般开发使用上述 Gradle 命令即可。

## 内置服务

仓库包含修改后的 scrcpy 4.1 服务源码和对应的内置服务文件，因此首次客户端构建不需要单独编译服务。客户端构建会检查服务文件 SHA-256 和版本标记。

修改服务源码后，先重新构建服务，再构建客户端：

```powershell
.\scripts\build-server.ps1
.\gradlew.bat :remote:assembleDebug --no-configuration-cache
```

脚本从 `ANDROID_HOME`、`ANDROID_SDK_ROOT`、根目录 `local.properties` 或 Windows 默认 SDK 目录查找 SDK，也可显式传入 `-SdkDirectory`。服务脚本同时运行服务端单元测试和 lint。

## 目录

- `scrcpy-android/app/`：实际客户端，对应根工程的 `:remote`。
- `scrcpy-android/vendor/libadb-android/`：ADB 库，对应 `:adb`。
- `scrcpy-android/vendor/scrcpy/`：完整 vendored scrcpy 源码，包含服务端修改。
- `app/`：最初的 Android 示例工程，保留供参考；其依赖和构建要求独立于 `remote`。
- `PHONE-MODE.md`：功能、行为和历史验证说明。
- `NOTICE`：上游来源、版本和本地修改说明。

源码以普通文件保存，不需要初始化 Git 子模块。原上游仓库内的构建说明和工具仍保留，构建本版本请以本文件的根工程命令为准。

## 来源与许可

本项目是在现有开源项目上的修改版，并非上游官方发布。上游作者和许可声明均予以保留：

- [scrcpy-android](https://gitlab.com/0xlena/scrcpy-android)：Apache-2.0。
- [scrcpy](https://github.com/Genymobile/scrcpy)：Apache-2.0。
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)：以其随附的 `COPYING`、`LICENSES/` 和各文件声明为准。

参见根目录 `LICENSE`、`NOTICE` 及各上游目录中的许可文件。
