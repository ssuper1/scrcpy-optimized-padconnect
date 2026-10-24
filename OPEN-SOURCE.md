# 开源来源与发布说明

本项目是在 scrcpy-android 0.5 上修改的独立版本，并非上游官方发布。
原作者的权利和署名保留，ssuper1 的版权声明只覆盖本地改动。

## 源码与许可

- 根目录 `LICENSE` 是完整 Apache License 2.0 条款。
- `NOTICE` 记录上游版本、作者、本地修改范围和源码来源。
- scrcpy-android 和 scrcpy 使用 Apache-2.0；libadb-android 使用其
  双重许可中的 Apache-2.0 选项，并保留各文件的其他适用声明。
- SPAKE2-Java / spake2-android 使用 LGPL-3.0，其原生依赖还包含其他许可。
  不能把整个安装包的所有第三方代码都笼统标为只有 Apache-2.0。
- 各依赖的声明见 `scrcpy-android/app/src/main/assets/THIRD_PARTY_NOTICES`，
  完整条款见随源码保留的许可文件及 `THIRD_PARTY_LICENSES/`。

当前修改版源码、构建脚本和历史在：
https://github.com/ssuper1/scrcpy-optimized-padconnect

0.6.15-phone 安装包对应 `v0.6.15-phone` 标签；历史 0.6.14-phone 对应 `v0.6.14-phone`。
之后的源码修改不会自动进入该安装包；发布后续版本时应创建对应的新标签，
保留该版本源码与构建脚本，并同步应用内和 Release 的源码信息。

## 查看安装包内的声明

从应用设置进入“开源许可”，可以离线查看组件说明、完整许可证、
原作者署名和本地 `NOTICE`。构建时自动生成 `OPEN_SOURCE_LICENSES`，
无需手动维护多份完整条款。0.6.15 安装包已包含这些完整声明。

## 替换 LGPL 依赖后重新构建

允许为了调试 LGPL 组件的修改而进行必要的逆向工程。
本项目不额外禁止这种行为。

1. 按 README 的环境要求安装 JDK 与 Android SDK。
2. 从组件说明中的固定版本地址取得 SPAKE2-Java 与 spake2-c 源码。
   SPAKE2-Java 使用子模块，构建时需按照其上游构建说明取得完整依赖。
3. 修改并按上游构建步骤生成兼容的 `spake2-android` AAR。
4. 在 `scrcpy-android/vendor/libadb-android/libadb/build.gradle` 中，将
   `spake2-android:2.2.1` 依赖替换为该 AAR；如引入额外依赖，也需一并配置。
5. 按 README 的根工程命令重新构建客户端。可用自己的密钥签名；
   开发版使用独立包名。自己的签名不能覆盖原签名安装包，但可以安装重构版本。

## 以后修改和发布时

- 保留原有版权、许可和适用的第三方声明，不将原作者署名替换成自己的。
- 修改上游文件时，在文件中保留或补充明显的修改标记；新增文件由 Git
  和 `NOTICE` 记录来源。校验和等机器读取文件不插入会破坏格式的注释，
  对应二进制的来源和改动记录在 `NOTICE` 中。
- 新增或升级依赖时，核对其许可、署名和完整条款，并更新随包声明。
- 确认发布 APK 中包含 `THIRD_PARTY_NOTICES` 与 `OPEN_SOURCE_LICENSES`，
  而不只是在仓库 README 中放一个许可证链接。
