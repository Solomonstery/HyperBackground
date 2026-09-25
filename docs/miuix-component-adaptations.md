# MIUIX 组件适配清单

本文记录 HyperBackground **当前代码已接入的组件适配入口**。这里的“已适配”指模块具备识别和接管这些结构的实现；实际页面能否命中，还取决于目标应用的 MIUIX 版本、视图结构、LSPosed 作用域与开关设置。材质可按配置使用自定义颜色、磨砂或柔光玻璃；具体效果以设备支持情况为准。

| 组件作用域项目 | 已接入的组件 / 场景 | 接管方式 |
| --- | --- | --- |
| **分组卡片** | MIUIX `PreferenceFragment` 中由 `RecyclerView.ItemDecoration` 绘制的分组卡片，包括使用三参数或四参数绘制入口的实现 | 在装饰器注册或绘制时发现实例，依据绘制、裁剪方法及字段结构接管卡面；不是修改每条 Preference 的 View 背景 |
| **独立卡片** | `CardView` / `HyperCardView`、`SmoothFrameLayout` 等自身带圆角卡面的 View；蓝牙已保存设备卡、省电与电池卡、设置「其他设置」推荐卡等结构 | 根据视图实际背景和圆角识别；设置推荐卡另有针对 `line_layout` 的接入点，详见下文 |
| **弹窗** | MIUIX `PopupView`、列表下拉弹窗、`HyperPopupWindow`、`DialogParentPanel2` 等具有独立圆角容器的弹层 | 接管弹层的外层背景或原生材质表面；列表内容和普通页面卡片不作为弹窗卡面处理 |
| **搜索框** | `SearchModeStubView`、`SearchActionModeView` 承载的 MIUIX 搜索表面 | 定位搜索模式的材质容器，在原生材质更新后同步应用配置，并处理退出搜索时的恢复 |
| **悬浮操作底栏** | `ResponsiveActionMenuView` 的悬浮（`isSuspend`）操作菜单 | 仅在悬浮状态处理背景与材质；贴底的普通操作栏保持原生表现 |
| **顶栏按钮** | MIUIX `ActionBarContainer` 中的顶栏操作按钮 | 按组件开关接管按钮背景；可选“顶栏按钮背景”让浮动胶囊背景常驻 |

## 独立卡片：具体接入了哪些 View

独立卡片的主路由是**按卡面结构识别**，不是将每一种 View 的类名写入白名单。下表区分了代码中明确引用的 View／资源和依照结构识别的实例：

| View / 页面实例 | 实际接管位置与条件 |
| --- | --- |
| `CardView`、`HyperCardView` 类的圆角卡 | `CardSurfaceDetector` 检查 View 自身是否有背景、卡面形状和有效表面；`HyperCardView` 的原生 `RoundRectDrawable` 无法复制时，使用原始圆角半径重建材质背景。类名是形状线索之一，并非仅凭类名就接管。 |
| 名称包含 `SmoothFrameLayout` 的圆角容器 | 可作为独立卡片的形状线索；仍需满足尺寸、自有背景、表面不透明度及没有外层卡面等条件。弹窗容器由弹窗路径负责，避免重复接管。 |
| 蓝牙**已保存设备**的 `view_high_light_root` | 该条目自身有高亮卡面时，可走独立卡片识别。蓝牙**可用设备**即使复用 `preference_bt_icon_corner` 布局，绑定后若清除了该背景，就只作为分组卡中的普通行，不单独套材质。`view_corner` 这类透明 `CardView` 外壳也不能单凭圆角算作有效卡面。 |
| 省电／电池的卡片背景（例如 `selector_battery_card_bg`、`hp_card_bg_no_shadow_normal`） | 将 selector、layer-list、inset、ripple 等复合 Drawable 展开判断真正的填充和圆角；符合条件的承载 View 可走独立卡片路由，半透明圆角卡面也可识别。这里列的是代码中明确处理过的背景结构，并非所有使用该资源的 View 必然命中。 |
| 设置「其他设置」的 `RecommendPreference` → `line_layout` | 有明确接入点：在 `onBindView(View)` 后定位行内真正带背景的 `line_layout`，把这层卡面交给独立卡片材质；不把透明的 Preference 行或行内控件当卡片。 |
| 小米账号首页账户栏、权益栏目 | 由该 APK 的 MIUIX Preference 分组装饰器绘制的卡面负责；`LinearLayout` 和 `HyperCellLayout` 是内容行，不另加独立卡材质，避免蓝牙等分组列表叠加柔光。装饰器注册入口在部分混淆 APK 中按方法签名识别。 |
| 原生大尺寸圆角 Button（例如退出按钮） | 根据按钮自身尺寸和背景的圆角形状匹配；保留原有布局参数、内边距、背景给出的最小高度及圆角，不接管外层 footer。 |
| 「我的设备」样式 1／2／3 的自绘卡 | 不经过上述通用 View 识别；由 `applyCustomCardMaterial` 使用传入的圆角半径单独接管自绘卡面。 |

通用判定的最低尺寸是 **56 × 40 dp**。候选 View 还必须有自己的背景、能确认圆角形状、具有足够可见的卡面，并且没有已经接管卡面的外层容器；圆角确认后允许一定程度的半透明背景。首次布局时尺寸为零的 View 会在尺寸变化后补判。全宽 `HyperCellLayout` 分组行、只有透明外壳的分组行、小图标／普通按钮／遮罩及搜索框、弹窗、悬浮菜单等有专用路径的表面会避开独立卡片路由，防止重复叠材质。

## 其他组件的具体入口

- **分组卡片**：发现 `RecyclerView.addItemDecoration` 注册的装饰器，也检查已提前注册的装饰器；支持 `miuix.preference.PreferenceFragment` 内部装饰器。分组背景由装饰器绘制，不是 Preference 行的 `background`。
- **弹窗**：`miuix.popupwidget.widget.PopupView` 的内容容器、MIUIX 下拉列表弹窗的圆角容器、`miuix.appcompat.widget.HyperPopupWindow` 的容器，以及 `miuix.appcompat.internal.widget.DialogParentPanel2`。只有符合弹窗结构的 `SmoothFrameLayout2` 才走列表弹窗路径。
- **搜索框**：`miuix.appcompat.app.SearchModeStubView` 和 `miuix.appcompat.internal.app.widget.SearchActionModeView` 的搜索材质子层；原生 `SearchViewMaterialImpl` 重设效果后会再次同步材质。
- **悬浮操作底栏**：`miuix.appcompat.internal.view.menu.action.ResponsiveActionMenuView`，仅处理 `isSuspend` 为真的悬浮菜单。
- **顶栏按钮**：`miuix.appcompat.internal.app.widget.ActionBarContainer` 管理的操作按钮；“顶栏按钮背景”选项可将按钮浮动胶囊态设为常驻。

## 相邻功能

- **顶栏遮罩 / 模糊**：MIUIX `ActionBarContainer` 还单独处理顶栏遮罩及模糊表现；它与上表的“顶栏按钮”背景是两条不同的处理路径。
- **全局壁纸**：向允许的应用大页面注入全局背景，不属于卡片材质。
- **布局清理**：按应用开关扫描可能遮挡全局壁纸的页面表面，属于背景清理，不是 MIUIX 组件材质。组件总开关默认关闭；开启后可在软件作用域详情中逐应用关闭。

## 开关与适用范围

先在 LSPosed 中勾选目标应用，再确认「软件作用域」的整包开关、「组件作用域」的组件总开关及该应用详情中的组件开关。组件总开关关闭时，应用详情不显示对应的独立开关。即使开关均已开启，目标页面也必须具备上表对应的结构，才会被动态识别。

各组件的使用步骤、材质设置及常见问题见 [动态适配使用指南](dynamic-adaptation-guide.md)。
