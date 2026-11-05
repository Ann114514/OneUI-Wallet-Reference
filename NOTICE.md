# 来源和许可说明

## 汐语（XiYu）地区兼容

**文件**：`wallet-reference/src/main/java/dev/oneui/toolbox/samsung/wallet/WalletRegionCompat.java`。

地区兼容行为移植自汐语 **3.0/code8**（源应用包名 `com.keyscafe.unlock`）钱包分支 `kg0/fg0/l9/db`。上游材料内记录的项目地址是 <https://github.com/qiaoing888/XiYu>。

移植范围是三星钱包、三星支付框架、银联可信服务组件三个进程中的地区属性读取映射：五个地区属性，以及 Samsung helper 的三个地区 getter，返回 CHC/CN/China。未写入全局系统属性。Toolbox 另做 Xposed API82 接入、返回类型/签名保护、初始化和异常隔离适配。

交通卡入口/目录、钱包端校园卡、NFC提示、自定义卡面、刷卡卡面和Pass接入不是这份汐语地区分支自带的功能，不把整个钱包工程归称为汐语实现。

**许可状态**：历史材料包含 GPLv3 引用及当时README说明，但完整上游 LICENSE 文本和单文件授权范围尚未独立取得。保留来源和证据，不擅自增加未经核验的 SPDX 标识，也不把整个混合来源仓库重标为MIT或Apache许可证。上游正式许可可获取后应据实补齐。机器可读来源见 [`provenance/xiyu.json`](provenance/xiyu.json)。

## 其他实现与构建材料

其他钱包兼容实现来自 OneUI Toolbox 0.1.3-test1，基于当前原生接口行为开发；复制来源和哈希见 `snapshot.json`。本次剥离没有指定整个仓库的统一新许可证，公开可见不等于替所有上游权利人授予任意再分发权。

本仓库不提供三星反编译源码或原应用二进制。`src/transitDesignStubs` 是为编译独立 Activity 书写的最小类型声明，运行时使用钱包/AndroidX 的真实类，声明不进入最终Activity DEX。

| 材料 | 来源与说明 |
| --- | --- |
| Gradle Wrapper | Gradle项目构建引导文件，保留脚本版权头；按其Apache-2.0许可及通知分发，许可副本位于 `licenses/Gradle-LICENSE`，取自 <https://github.com/gradle/gradle/blob/v8.13.0/LICENSE> |
| Xposed API82 | compileOnly，仓库不随附API JAR；<https://github.com/rovo89/XposedBridge> |
| 交通联合PNG | 原使用者提供的标识素材，仅作卡面显示；标识和商标权属于相应权利人，本仓库不另行授权 |
| Samsung Wallet/Pass及其接口名 | 用于说明兼容目标，项目不是三星官方产品，不分发三星APK/DEX、证书或服务端数据 |

请保留代码中的汐语来源注释以及本说明；有正式上游来源更新时，请同步更新来源清单。
