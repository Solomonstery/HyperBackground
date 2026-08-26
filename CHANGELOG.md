# 版本说明

GitHub Actions 会按照 APK 的实际 `versionName` 提取对应章节，并写入 GitHub Release 描述。版本名包含 `test`、`alpha`、`beta`、`rc` 或 `dev` 时会自动标记为 Pre-release。

## 1.4.1-beta1

- 修复：强制深浅色设为“跟随系统”后无法真正还原、导致设置等应用的系统深浅色开关被锁死、无法切换的问题。
- 根因：旧逻辑在“跟随系统(FOLLOW)”时直接 return，从不撤销此前通过 `UiModeManager.setApplicationNightMode` 写入的 per-app 夜间模式覆盖，覆盖持久残留，即便停用模块或强杀进程也无法恢复。
- 改进：`onResume` 时始终调用 `applyApplicationNightMode`；FOLLOW 显式设为 `MODE_NIGHT_AUTO` 主动撤销 per-app 覆盖，DARK/LIGHT 分别设为 `MODE_NIGHT_YES`/`NO`。
- 恢复方法：启用模块并勾选作用域后，在 HyperBG 将强制深浅色设为“跟随系统”，逐个打开受影响的作用域应用（尤其是设置）触发 `onResume`，各进程会各自把 per-app 夜间模式还原为 AUTO。

## 1.4.0

- 重构：使用 Miuix 风格重构 UI
- 重构：迁移到 LibXposed API 101 实现

## 1.3.9-beta2

- 修复：重启作用域对部分包名（如电话、安全中心）失效的问题。旧实现把所有包用 `;` 串成单条 `su -c` 命令、并在 `waitFor` 之后才读取输出流，当某包输出较多写满管道缓冲时子进程阻塞、`waitFor` 超时，导致排在其后的包无法结束。
- 重构：参照 HyperIsland 的 RootShell，新增 `RootShell`（`su -c` 执行 + 独立守护线程实时排空 stderr，主线程读 stdout 后再 `waitFor`），避免管道阻塞。
- 改进：`forceStopScopes` 改为逐包独立执行并聚合结果，单个包失败不影响其余包；重启反馈区分“Root 不可用”与逐包成功/失败，提示“已重启 N 个，失败：xxx”。
- 强制深浅色：将主题（`SettingsThemeOverride`）与文字色（`TextColorOverride`）强制从仅 `com.android.settings` 进程放开到所有作用域进程，使应用详情页等由其它进程提供的页面也能被模块自定义深浅色控制。
- 测试重点为：作用域工具的常规/完整重启是否全部成功（失败会提示具体包名）；应用详情页及其它非设置进程页面在强制浅色/深色下配色是否跟随模块设置。

## 1.3.9-beta1

- 修复：应用详情页（应用信息）不跟随模块自定义深浅色、只跟随系统的问题。该页 header 卡片由 miuix 组件绘制，走 `ViewUtils.isNightMode(context)` 读取 Activity 实际 Resources 的 `Configuration.isNightModeActive()`，此前仅替换 `attachBaseContext` 的 Context 无法覆盖到它。
- 改进：在 `Activity.onCreate/onResume` 阶段直接改写 Activity 使用的 Resources 的 Configuration（uiMode NIGHT 位），使 miuix 组件的深浅色判定也跟随模块设置。
- 兜底：在设置进程通过 `UiModeManager.setApplicationNightMode` 统一改写进程级夜间模式，覆盖绕过 Activity Context 的组件。
- 测试重点为：应用详情页在强制浅色/强制深色/跟随系统三种模式下卡片与文字配色是否正确，以及其它设置页面配色与背景是否保持正常。

## 1.3.9

- 汇总 1.3.8 测试线的修复，作为稳定版发布。
- 修复：设置主页背景通道失效——`com.android.settings.MiuiSettings`（设置主页宿主）的生命周期 Hook 是 `applyHome` 的唯一挂载入口，早前误删导致浅色/深色两种主题下主页背景均不生效，现予恢复。
- 修复：设置搜索输入文字时整个页面短暂变黑，搜索加载层与窗口级搜索遮罩改为透明。
- 优化：还原桌面设置背景逻辑至已验证可用状态，保留旧版（OS3）桌面设置背景生效路径。
- 新增：关于页“本次版本说明”改为动态读取，按当前版本从打包的 CHANGELOG.md 自动解析对应条目；解析支持多种列表符号、跳过代码块并对版本号做归一化匹配，找不到精确版本时回退显示最新说明。
- 说明：LSPosed 模块描述扩写为功能概览，便于在模块列表中直观了解覆盖范围。
- 修复：模块仓库客户端读不到简介——`xposeddescription` 由资源引用 `@string/...` 改为写死的字面字符串（部分客户端只读 Manifest 原始值、不解析资源）。
- 兼容性：当前 HyperOS 桌面二级设置页由 Flutter/Rust 渲染，模块的 View 树背景注入对其无效；本版仅确保旧版桌面设置背景原本能生效的路径不被破坏。

## 1.3.8-test6

- 新增：关于页“本次版本说明”改为动态读取，按当前版本从打包的 CHANGELOG.md 自动解析对应条目；找不到精确版本时回退显示最新说明并标注实际运行版本。
- 优化：清理针对桌面设置的无效 Hook 残留（`MiuiSettings` 生命周期与 `com.miui.home` 原生 Activity 精确 Hook），这些通路经反编译与运行日志确认无法命中。
- 修复：还原桌面设置背景逻辑至 test5 可用状态，恢复 `home` 分支的短路判断，回退 test6 早期为适配 Flutter 桌面所做的改动。
- 兼容性：当前 HyperOS 桌面二级设置页由 Flutter/Rust 渲染，模块的 View 树背景注入对其无效；本版仅确保旧版（OS3）桌面设置背景原本能生效的路径不被破坏。
- 兼容性：仅调整桌面设置这一项，不影响设置搜索遮罩透明化等其它已生效逻辑。
- 测试重点为关于页版本说明显示、桌面设置页面背景、页面进入退出以及现有设置搜索功能。

## 1.3.8-test5

- 修复设置搜索输入文字时整个页面短暂变黑的问题，搜索加载层和窗口级搜索遮罩改为透明。
- 测试重点为设置搜索栏输入、搜索结果加载以及深色主题下的背景连续性。

## 1.3.8-test4

- 移除窗口重新获得焦点时的全局背景重应用，避免搜索栏获取焦点和软键盘弹出时触发背景清理造成黑屏闪烁。
- 移除全局布局监听器，避免搜索输入、结果刷新和键盘尺寸变化时反复递归扫描并清理整棵页面视图树。
- 全局背景仍在 Activity 创建、恢复和内容替换后按需挂载，保留生命周期调度去重。
- 测试重点为搜索栏连续输入、键盘弹出、搜索结果刷新以及二级菜单进入速度。

## 1.3.8-test2

- 修复电话服务“移动数据”二级页面的背景宿主判断，改为检查 `android.R.id.content` 的父级是否为 `ActionBarOverlayLayout`。
- 命中 MIUIX 转场容器时将自定义背景挂载到父级，避免背景跟随页面滑动并与旧页面短暂叠加形成虚影。
- 未命中已知层级时继续使用内容层宿主，保留不同 HyperOS 版本的兼容回退。
- 测试重点为进入和退出移动数据页面时的滑动转场、背景连续性及返回后的页面显示。

## 1.3.7-test3

- MIUIX 二级页面的全局背景仅在识别到 `ActionBarOverlayLayout` 时提升到其根层，不再触碰 DecorView。
- 状态栏、返回栏和大标题区域改为透明，背景图连续延伸到屏幕顶部。
- 直接清除 `ActionBarContainer` 内部绘制的 primary background，解决普通 `View.background` 清理无效的问题。
- 保留应用设置顶部功能卡、隐私页切换条和正文卡片的原生半透明样式。
- 已成功的设备互联与省电管理继续沿用内容层挂载，不进入顶栏提升逻辑。
- 测试版恢复正式包名 `com.ciallo.hyperbackground`，使用与正式版相同的私有 Release 签名并由 GitHub 标记为 Pre-release，可直接覆盖安装。
- 构建作用域校验由 8 项同步为 9 项，加入 `com.xiaomi.misettings`。

## 1.3.7-test2

- 回滚 1.3.7-test 错误的 DecorView 底层挂载，设备互联恢复为 1.3.6 已验证的 `android.R.id.content` 实现。
- 电话服务、小米账号、主题壁纸、手机管家等普通 Android / Miuix 页面统一使用内容层背景，并保留异步布局后的重复挂载。
- 新增 `com.xiaomi.misettings` 作用域，覆盖“健康使用手机”等由独立应用承载的页面。
- 增加 Instrumentation 生命周期兜底，处理未经过标准 Activity 基类回调的页面。
- 正式版与测试版的 Xposed 运行字段按包名隔离，避免两个模块同时安装时会话对象冲突。
- 新增 Hook 读取记录，可直接确认目标进程是否成功注入并读取全局背景。
- 系统桌面为 Flutter / Rust 共享运行时页面，本测试版保留注入诊断，不再使用会导致其他页面回归的通用 Surface 覆盖。
- 测试包继续使用 `com.ciallo.hyperbackground.test` 与调试证书，不覆盖或改动 1.3.6 正式版。

## 1.3.7-test

- 首轮多软件背景 Hook 兼容测试（已由 1.3.7-test2 替代）。

## 1.3.6

- 全局背景新增电话服务、小米账号、主题壁纸、系统桌面、手机管家与省电管理作用域。
- Settings 普通二级页面继续使用全局通道，设置主页与“我的设备”保持独立。
- 登录、授权、锁屏凭据、支付、拨号、紧急呼叫及浮动窗口保持系统原样。
- 调色盘升级为 12 色预设、HSV 色相/饱和度/明度滑杆和可编辑 HEX 输入。
- HEX 输入支持 `#RRGGBB` 与 `#AARRGGBB`，并使用 ASCII 键盘以便输入 A–F。
- 修复本地构建产物文件名没有跟随 APK 实际版本的问题。
- Release 构建改用 GitHub Actions Secrets 注入私有 PKCS#12 密钥；仓库不再保存签名文件或密码。
- 1.3.6 是私有签名迁移起点，与 1.3.5 及更早公开签名不兼容；从本版起保持签名连续。

## 1.3.5

- 移除背景页冗余的 HyperOS / Compose / MIUIX 介绍卡片。
- 关于页新增正式版版本验证、最新正式版状态和本次版本说明。
- 修复 Miuix 自定义主题色没有进入 Monet seed 配色流程、导致调色盘始终呈蓝色的问题。
- 正式版检查只读取 GitHub `releases/latest`，不会把 test / alpha / beta / rc 误判为正式更新。
- 包名和固定签名保持不变，可覆盖安装旧版。

## 1.3.3-test

- 完整移除失效的“主题通道实验”，包括配置界面、Provider 字段、Hook 入口与实现类。
- 全局背景继续通过 Activity 生命周期与页面根容器处理，不再拦截系统主题属性。
- README 仅保留当前正式版信息，测试版变更集中到本文件和 GitHub Releases。
- 构建链为正式版和测试版自动生成发行说明、构建信息与固定签名信息。
- 包名和固定签名保持不变，可覆盖安装旧版。

## 1.3.2-test

- 模块无自定义背景时，底色跟随 MIUIX 浅色或深色主题。
- 新增模块卡片透明度，并将原透明度选项明确为背景图透明度。
- HyperBG 大标题下新增“一言”区域，支持刷新、自定义 API 和 JSON 点路径字段。
- 保留主页、我的设备和全局三套独立背景通道。

## 1.3.1

- 配置界面迁移为 Kotlin、Jetpack Compose 与 Miuix KMP。
- 新增浅色、深色、跟随系统、Monet 壁纸取色和自定义主题色。
- 重新组织主页、我的设备和全局背景三通道。
- 保留媒体选择、透明度、模糊、文字颜色和 Settings 外观设置。
- 加入制作者苍簇、酷安与 GitHub 入口。
- 固定模块签名，后续版本可以覆盖安装。
