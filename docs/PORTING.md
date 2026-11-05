# 接入与移植

## 最小入口

将 `wallet-reference` 作为 Android library 接入自己的 LSPosed 项目，或复制对应 Java/res/assets 和两个 Activity 源集。示例总入口位于 [`examples/WalletOnlyModule.java`](../examples/WalletOnlyModule.java)。

```java
if (WalletModule.isTarget(param.packageName)) {
    WalletModule.install(param);
    if ("com.samsung.android.spay".equals(param.packageName)) {
        BeijingStudentCardCompat.install(param);
    }
}
```

`BeijingStudentCardCompat` 由总入口单独调用；只复制 `WalletModule.install` 会漏掉该项。复制示例 `xposed_init` 到宿主 assets，并合并 scope 数组及 manifest metadata。参考库自身不提供应用入口、总开关或自动作用域配置。

## 作用域

| 目标 | 包名 |
| --- | --- |
| 三星钱包 | `com.samsung.android.spay` |
| 三星支付框架 | `com.samsung.android.spayfw` |
| 银联可信服务管理器 | `com.unionpay.tsmservice` |
| Samsung Pass | `com.samsung.android.samsungpass` |

按所选作用域自动启用。Pass 进程只接准备状态补丁，不执行三组件的国行地区属性映射。不需要把北京一卡通、桌面或 SystemUI 加入钱包作用域。目标应用重启后加载新 Hook；避免 Toolbox 和移植后的同功能模块同时作用于一个进程。

## 包名和资源：必须同步处理的地方

Java 包路径保留为 `dev.oneui.toolbox.samsung.wallet`，资源 namespace 为 `dev.oneui.toolbox`。宿主 applicationId 可以不同，但必须同步以下位置：

1. `WalletTransitCardLogo.PACKAGE` 从模块 APK assets 读取 `transit_union_logo.png`，需换成自己的宿主 applicationId。
2. `WalletTransitDesignModule` 中的 `createPackageContext("dev.oneui.toolbox", 0)` 从模块 assets 读取 `transit_design_activity.dex`，也要换成自己的 applicationId。
3. 如果更改资源 namespace，需同步 `WalletDownloadsActivity` 的 `dev.oneui.toolbox.R` 导入。
4. 如果给独立 Activity 改类名或包路径，需同步 `src/transitDesign` 的 package 以及 `WalletTransitDesignModule.ENTRY`。这个类不应直接编进宿主的主 DEX。
5. 若要改 `dev.oneui.toolbox.action.PASS_RESTORE_READY` 提示 action，发送和接收两端必须一致。它不携带敏感数据，接收后仍重新读取原生权限保护的状态。

原项目的硬编码保留是为了对应实现，不代表移植者可以省略上述修改。

## 公交卡独立 Activity

`src/transitDesign/java` 是真正的公交卡 Activity，继承钱包运行时已有的 `SpayBaseActivity`。`src/transitDesignStubs/java` 仅为编译声明，不是三星类实现。

构建步骤与原工程一致：先编译声明，再编译独立 Activity，通过 D8 只将 Activity 及其返回回调编成 `transit_design_activity.dex`，作为 AAR assets 提供。请保留 `wallet-reference/build.gradle` 底部相关任务。不要将 stub 目录加入宿主的 main Java 源集，也不要把桩类打入 APK。

运行时通过 `InMemoryDexClassLoader`、钱包 ClassLoader 父链和系统 `InvocationHandler` 边界加载。标记过的专用 Intent 使用钱包已声明的普通 Activity 组件作注册承载；只有同时匹配组件和私有标记时才替换为独立实现，其他原生启动不拦截。这个承载点依赖当前钱包版本，移植时需要重新核对 manifest 和主题。

页面沿用门卡原生设计 XML、浮动标题/按钮和主题，业务状态独立。打开使用普通 `startActivityForResult`，返回使用原生 AndroidX 返回分发及 `finish()`，没有手工覆盖转场。选卡、恢复默认和 Logo 修改先预览，保存才提交；卡面库增删保留原有即时行为。

## 初始化与版本

地区 getter 在 Application 构造前处理，避免地区值提前被缓存；页面和图片绑定等待真实 Activity/ClassLoader。不能将所有 Hook 机械放到同一个初始化时点。

SM-S9180、559700323、521010900 等守卫是实际适配边界。更换版本后，分别核对签名、字段、资源、调用者限制及数据语义。原生签名/认证检查和服务端结果保留；不要通过伪造成功或跳过认证来掩盖接口不匹配。

## 可选下载界面

`WalletDownloadsActivity` 需要宿主的 Material3/AppCompat 主题。`wallet_downloads.xml` 中三个地址默认空，按钮不可用；填入自己有权提供的地址即可。页面只打开下载页，不执行 APK 安装。

Xposed API82 仅为 compileOnly，不能打入模块；Material1.14.0、AppCompat1.7.0 由构建系统获取。首次构建需要可访问对应 Maven 仓库；`--offline` 仅适用于依赖已经缓存的机器。
