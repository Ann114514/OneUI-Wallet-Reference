# 功能实现说明

| 范围 | 源文件 | 接入概要 |
| --- | --- | --- |
| 汐语地区分支 | `WalletRegionCompat` | 三目标进程的五个地区属性、三个 Samsung 地区 getter；CHC/CN/China；不写系统属性 |
| 交通卡入口和目录 | `WalletTransitEntryCompat` | 原生菜单能力和城市目录分别适配，运营商别名限制在对应调用作用域 |
| 校园卡地区识别 | `WalletCampusCardCompat` | 北京一卡通、校际通与上海校园联名卡三条已确认接口；仅作用于授权后的指定地区校验 |
| NFC 误报 | `WalletAccessNfcCompat` | 只在门卡读取和银行选择页，区分独立开关支持状态与实际读写开启状态 |
| 门卡自定义设计 | `WalletCardArtCompat`、`WalletCardArtCropView`、`WalletCardArtLibraryView`、`WalletCardArtNameContrast` | 沿用原生编辑页面，提交成功后保存私有应用副本，保留选择/裁剪/删除与名称对比度 |
| 公交卡独立设计 | `WalletTransitCardArtCompat`、`WalletTransitDesignModule`、`TransitDesignActivity` | 详情页入口，独立 Activity 和草稿状态；无顶部分类和名称区域；恢复默认及 Logo 选项位于页内 |
| 公交卡绑定与标识 | `WalletTransitCardArtBindings`、`WalletTransitCardLogo`、`WalletTransitCardArtPanel` | 详情、列表和用卡画面绑定；默认卡面下 Logo 开关置灰并保留勾选 |
| 图片存储 | `WalletCardArtStore` | 私有图库和逐卡独立副本；原子文件保存、有界缓存、哈希文件名 |
| 刷卡小窗 | `WalletTapArtCompat` | 原生 `inflateCardImage(ImageView,WfCardModel)` 中直接解析本次卡片身份 |
| Pass 兼容 | `WalletPass*`、`PassPreparationState` | 见专门的流程文档 |

上述其他功能不是汐语地区分支自带的 Hook。相关混淆类与函数名仅对应当前钱包版本。

## 校园卡地区识别

实际修正的是钱包读取的 `ro.boot.carrierid`：仅在指定校园卡资格校验期间将 `TGY` 返回为 `CHC`，不写系统属性。当前接入关系如下：

| 渠道 | 原生请求 | 卡类型 |
| --- | --- | --- |
| 北京一卡通 | `cn.com.bmac.nfc` 调用 CMN，issuer 为 `BEIJING_MOT_V2_CAMPUS` | `TRANSIT_CARD_TYPE_BJMOT_V2` |
| 校际通 | `cn.com.bmac.xjt` 调用 CMN，issuer 为 `BEIJING_MOT_V2_XJT` | `TRANSIT_CARD_TYPE_BJMOT_XJT` |
| 上海交通卡校园联名卡 | SPTC 接口，issuer 为 `SHT_2IN1`；调用方仍须通过钱包原生签名/服务授权 | `TRANSIT_CARD_TYPE_SHMOT` |

三个渠道均覆盖检查、预发卡和发卡的成功授权回调；卡类型、issuer、请求和回调必须对应。原生校验完成后，才在指定城市检查内局部映射；不改签名验证、学校资格或发卡结果。校际通的原生 issuer 使用北京服务标识，不代表只限北京或西安。

这是当前代码可确认的三条接入路径，不能当作完整校园卡 App 目录。校园卡目录的数据模型是可变列表，本地配置未列出完整名单。CMN 的 `cn.com.whty.cqt` / `CHONGQING` 属普通交通卡接入，当前不因共用接口而列入校园卡修补。

## 刷卡卡面

旧“按原图片地址/预置设计反查卡片”的实现已经删除。现在接入实际刷卡小窗 `CardSwipingMiniWindowActivity.inflateCardImage`，获取已经由原生事件确定的 `WfCardModel`：交通卡按该模型 ID 定位对应原生卡片，门卡按模型里的 AID 定位卡片，继而使用编辑器相同的私有卡面键。

共用官方图片或预置设计不会作为辨别身份的依据。ImageView 被复用时先清掉旧卡绑定；原生异步图片回调通过已有渲染绑定保持对应自定义图。只更换显示层，不修改刷卡、扣费、NFC 或安全元件的数据。

## 公交卡编辑

页面使用详情页已加载的原生 Drawable 副本，并优先加载当前实际应用的私有图片。没有原图时回退钱包原生图片加载器。旧图库选中记录不会在用户未重新选择时覆盖当前应用副本。

独立页内的卡面选择、恢复默认、交通联合标识均为草稿，保存才提交；默认模式只是禁用 Logo 选项，不清空勾选偏好。图库图片的添加与长按删除保持原有行为，已应用的独立副本不会随图库删除而被清空。

图库图片、加号与选中遮罩按原生 CardView 尺寸和圆角对齐；公交卡详情内设置组使用原生背景轮廓裁剪点击反馈。仅作用于对应钱包视图。

## 不包含的内容

- 八达通强制入口/功能位或模块加载实验补丁；保持原生判断。
- 北京一卡通进程 Hook。
- 银行/银联绑卡次数与服务端风控解除。
- 自动更换钱包、清数据、重置账号、删除卡片或代用户确认快速访问添加。

## 本快照的清理

基于 test92，0.1.3-test1 净减少180行钱包代码：删除无引用旧图片接口、旧内嵌图库布局、无用字段与重复分支，合并相同图片读取并预编译原校验规则。没有改变已适配功能判断，也没有测量或宣称性能提升幅度。
