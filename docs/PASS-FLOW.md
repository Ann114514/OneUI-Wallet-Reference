# 国行钱包 Samsung Pass 接入流程

## 为什么不能只显示入口

入口、钱包服务身份、Pass 原生引导、账号云恢复、真实设备连接、快速访问卡片是不同阶段。入口可见不等于完成注册，连接成功不等于数据已经恢复，也不等于快速访问卡片已经添加。

当前实现沿用 Pass 的真实认证与钱包连接，不把错误返回改成“成功”，不伪造密码条目或固定数量。

## 类与职责

| 类 | 职责 |
| --- | --- |
| `WalletPassEntryCompat` | 恢复原生 dashboard 能力入口；独立注册存在时在钱包端选择对应港区 Pass 数据分区 |
| `WalletPassRegistrationCompat` | 独立港区真实 signup/signin；加密保存；只在原生 SDK Provider 权限检查成功后向 Pass 提供对应身份 |
| `PassPreparationState` | Pass 进程内原生引导、恢复请求、完成标记和连接状态 |
| `WalletPassConnectFlow` | 用户请求、转圈、前后台切换、引导返回、同步完成续接与原生连接 |
| `WalletPassPendingStore` | 加密保存短期续接元数据，关联账号并带过期时间 |
| `WalletPassDataReloadCompat` | 在原生页面恢复和 Provider 通知后触发有界原生刷新 |

## 执行顺序

1. 用户点击原生 Pass 连接入口。拦截的是 `Instrumentation.execStartActivity` 中的 `walletpass://migration` / `migrationlinkonly`，不是某一个容易漏掉的菜单回调。
2. 使用当前三星账号和真实设备证书，经三星 GLD 获取港区服务地址，执行独立的真实 signup/signin。结果使用 AndroidKeyStore AES/GCM 保存到钱包私有存储，并与账号/设备校验关联；不替换整个国行钱包环境。
3. 向 Pass 原生 `WalletSettingsProvider.getSignUpState` 查询状态。若未完成原生引导，打开原生 signup 流程。返回码本身不作为唯一完成证据，再读实际 `isSignedUp`。
4. 已完成引导但尚无恢复完成证据时，观察原生云同步；如果当前没有恢复在进行，则请求原生 `SCloudSyncManager.initSamsungCloudRPCProfile()`。不代替原生账号授权，也不改系统同步开关。
5. 由原生 `scloud_last_sync_time` 或实际密码同步完成回调判断已恢复。完成之后，后续排队的周期同步不应重新阻断连接。不能按固定密码数量、某张页面数字或单纯等待几秒判定恢复。
6. 准备完成后以原生 `migration_caller=16`、`toolbox_prepared_connect` 标记进入现有真实 walletSignIn/requestLink 流程。内部 prepared 标记避免自身再次拦截循环，不是服务端成功凭据。
7. 返回后读取原生 `getWalletMigrated` 确认真实连接。未连接则保留有界错误处理。
8. 尚未添加快速访问卡片时，调用原生 `PassCommonInterfaceImpl.addPassCardActivity` 打开“要将智能密码管家添加至快速访问吗？”页面；交接时清理待续接操作，**由用户点击添加或取消**。

## 跨页面与同步完成续接

宿主 Activity 销毁只分离 UI，不直接丢弃用户已请求的操作。重新进入钱包 Home 时关联当前 Activity，避免重用已销毁 Activity 的 token。

待续接 Intent、账号哈希、阶段与到期时间用已有 KeyStore 机制加密，保留 15 分钟。用户取消等待或完成页面交接后清除。前台轮询有时间上限；到达上限停止转圈/轮询，但在有效期内仍可由真实完成提示或前台恢复续接。

Pass 的恢复完成广播只携带“请重新检查”的提示，定向发给钱包，不携带条目、密码、卡号或授权令牌。钱包收到后必须重新查询原生权限保护的 Provider。导出的广播接收器不把收到广播本身当成恢复成功。

## 数据与显示

国行钱包和外版 Pass 的数据分区有区别。`WalletPassEntryCompat` 在已经存在有效独立注册时，将钱包原生 `whereCheckChina` 的相应筛选转换为港区选择；不会 Hook Pass 全局数据库查询，也不会扫描或导出密码库。

钱包重置可能连带重置 Pass 的本地状态。用户发现有时必须先完成 Pass 原生引导并等待账号恢复后，再连接钱包。因此不能把“数量不对”只修成一个显示数字；应处理真实恢复时序。

## 重要边界

- Pass 进程不执行国行钱包三组件的地区属性映射。
- 原生 SDK Provider 的签名/调用者校验先执行，补充状态/身份只在成功返回后处理。
- 所有账号令牌、证书与设备身份在使用者本机运行时获取；仓库没有这类真实值。
- 没有自动清数据、重置钱包、删除卡片或重新绑卡的逻辑。
- 银行/银联的发卡次数与风控限制不属于这套界面/兼容代码的解除范围。
- 版本升级要同时核对 Wallet 与 Pass，不能只更改钱包版本守卫。

## 等待 UI

使用系统主题的原生 ProgressBar，无可见“正在注册”文字。等待窗口全屏透明，转圈位于屏幕中央；状态栏透明、关闭状态栏对比度遮罩，并继承钱包当前状态栏图标深浅样式。这里只改等待弹窗本身。
