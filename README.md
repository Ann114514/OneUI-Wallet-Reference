# OneUI Wallet Reference

从 **OneUI Toolbox 0.1.3-test2 / code97** 剥离的三星钱包源码，供 LSPosed 模块作者阅读和移植。保留钱包相关实现，不包含桌面、锁屏、SystemUI 等其他工具箱功能。

本仓库是可构建的 Android **参考库**，输出 AAR，需要接入自己的 LSPosed 宿主；不是可直接安装的钱包或独立模块 APK。快照日期：2026-11-05。

## 汐语来源标注

**地区兼容部分移植自汐语（XiYu）3.0/code8**，对应 [`WalletRegionCompat.java`](wallet-reference/src/main/java/dev/oneui/toolbox/samsung/wallet/WalletRegionCompat.java)。原分支为 `kg0/fg0/l9/db`，处理三星钱包、支付框架和银联组件的地区属性读取。Toolbox 将其适配为 Xposed API82，并补充初始化、签名检查和异常隔离。

上游项目地址：<https://github.com/qiaoing888/XiYu>。历史材料记录有 GPLv3 引用，完整上游许可文本及适用范围尚未独立核验。源码头部、[NOTICE](NOTICE.md) 和[来源清单](provenance/xiyu.json)均保留说明。没有把整个工程冒称为汐语代码，也没有擅自给混合来源代码指定统一许可证。

## 内容

| 功能 | 实现入口 | 说明 |
| --- | --- | --- |
| 三组件地区兼容 | `WalletRegionCompat` | 汐语移植；仅改目标进程 getter 结果 |
| 交通卡入口、城市及卡种目录 | `WalletTransitEntryCompat` | 入口和目录分别处理，保留实际服务判断 |
| 校园卡地区识别兼容 | `WalletCampusCardCompat` | 北京一卡通、校际通和上海校园联名卡的检查、预发卡、发卡校验内局部映射 carrierid；不注入发卡 App |
| NFC 读写开关误报 | `WalletAccessNfcCompat` | 门卡、银行卡选择页；不全局屏蔽提醒 |
| 门禁卡自定义卡面 | `WalletCardArtCompat` 等 | 原生设计页增加自定义、相册、裁剪、卡面库和长按删除 |
| 公交卡独立设计页 | `WalletTransitDesignModule` 等 | 独立 Activity/编辑状态，预览后保存；恢复默认、交通联合标识设置 |
| 刷卡弹出卡面 | `WalletTapArtCompat` | 直接使用刷卡小窗的实际卡片对象，不按图片反查 |
| 国行钱包 Samsung Pass | `WalletPass*`、`PassPreparationState` | 真实注册、原生引导、云恢复、连接、快速访问确认与刷新 |

交通卡、学生卡、NFC、卡面与 Pass 是 Toolbox 中结合当前钱包原生行为另行实现的部分，不属于此次汐语地区分支移植。八达通保留原生判断，仓库不带强制开启或隐藏入口的实验 Hook。

## 适配基线

| 项目 | 基线 |
| --- | --- |
| 设备 | Samsung SM-S9180 |
| 国行 Wallet | 5.5.97 / 559700323 |
| Samsung Pass | 5.2.10.10 / 521010900 |
| 编译 | JDK21、SDK36、Gradle8.13、AGP8.9.2 |

多个实现带精确机型/版本守卫，NFC 修正还要求 API35+。`minSdk29` 是编译下限，不等于所有机型和系统均可用。移植到其他版本需核对原生类、混淆签名和行为，不能只删版本判断。

## 构建

安装 Android SDK36，设置 `ANDROID_HOME` 或本地 `local.properties`：

```sh
./gradlew :wallet-reference:assembleDebug
```

Windows 使用 `gradlew.bat`。默认通过 Xposed Maven 获取 API82；也可使用自己的 API JAR：

```sh
./gradlew -PxposedApiJar=/absolute/path/to/api-82.jar :wallet-reference:assembleDebug
```

输出：`wallet-reference/build/outputs/aar/wallet-reference-debug.aar`。构建会生成公交卡独立 Activity DEX 并装入 AAR assets；Wallet/AndroidX 编译桩不会打进产物。

## 移植与验证

- [接入指南](docs/PORTING.md)：入口、作用域、依赖、包名与独立 Activity 构建。
- [功能实现说明](docs/FEATURES.md)：各功能范围与关键接入点。
- [Pass 流程](docs/PASS-FLOW.md)：注册、原生恢复、连接和确认阶段的关系。
- [验证与限制](docs/VALIDATION.md)：当前设备证据及移植边界。
- [`snapshot.json`](snapshot.json)：源文件和发布文件的 SHA-256 对照。

参考库构建结果见 [`publication-check.json`](publication-check.json)。编译、安装、Hook 加载和可见效果分别记录，不将其推断为所有银行/城市/固件均已通过测试。校园卡新增路径的原生接口已核对，实际发卡仍待相应服务验证。

仓库没有账号、密码、卡号、令牌、设备日志、截图、三星 APK/DEX 或本机签名密钥。需要的三星应用及账户认证由使用者自行准备。
