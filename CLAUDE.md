# 口碑雷达 · 项目约定

Android App：从附近外卖/餐厅里按「口碑」筛店。Kotlin + Jetpack Compose + Material 3。

## 架构

```
:app   (Compose UI)  ──►  :agent (LLM 客户端、顾问智能体、导入提取器)
         │                      │
         └──────►  :data (高德/百度 POI、地理编码、演示数据、候选池)  ──►  :core (模型、口碑评分、筛选)
```

评分是确定性、可解释的内核；LLM 只负责听懂需求、读评价、把截图变数据。**不要为了 UI 改动碰 core/data/agent。**

构建：JDK 17+ / compileSdk 37 / `./gradlew test`（825 条，全绿是基线）/ `./gradlew assembleDebug`。

---

## 视觉：深海蓝系统

品牌视觉来自社区「鲸鱼娘 / 蓝色大肥鱼」形象的风格拆解，**只取视觉语言，不取角色**。完整规范见 `../whale_style_assets/whale_style_guide.md`（项目外，只读参考）。

### 色阶

```
ink_900   #062251   最深文字、轮廓
blue_800  #0E3D7A   深位背景
blue_700  #14305E   卡片标题
blue_600  #2676ED   主强调（seed）
blue_500  #0D9EF0   高亮、链接
cyan_400  #5CA0C4   次级强调
cyan_300  #65CBF1   图表、进度
cyan_100  #A4E9F5   浅底
gold_300  #EBD69E   徽章边、星级
gold_500  #C9A961   描边、分割
blush_200 #F5C8C3   弱警示
violet_700 #363F6A  暗色卡片
```

### 五条造型规则

1. 单色相纵深：只走蓝一个色相，靠明度拉层次；金色点缀面积 ≤ 3%
2. 描边用深蓝 `#1C2A4A`，不用纯黑
3. 转角全圆，不出现尖角
4. 四段渐变 `#1C3064 → #38609C → #5CA0C4 → #A0D0E4`，用于按钮/进度环/评分环
5. 留白靠纯白切分，弱化 divider

强调渐变：`#2676ED → #65CBF1`（135°）。

### 语义色

好评用低饱和墨绿 `#2E7D46`（深色模式 `#9CD9AE`），不用草绿；警示用暖金 `#8A6A20`（深色 `#EBD69E`）。语义色在 `Theme.kt` 的 `KoubeiColors` 里，不进 `colorScheme`。

---

## 硬约束

**1. 素材不进 APK。**
`../whale_style_assets/` 下的角色图片是 CC BY-NC-SA 4.0，禁止商用。不要复制到 `app/src/main/res/`，不要打包进任何构建产物。只可用作颜色、圆角、线宽、图标造型的参考。要放吉祥物必须自绘原创形象。

**2. 品牌色不能被动态取色覆盖。**
`KoubeiTheme` 的 `dynamicColor` 必须保持 `false`。为 `true` 时 Android 12+ 会走 `dynamicLightColorScheme()` 从用户壁纸取色，`LightColors`/`DarkColors` 整个失效——模拟器看着变了、真机没变，就是这个原因。

**3. 不要为适配配色去改测试断言。**
除非测试里硬编码了旧色值。

---

## 代码风格

- 主题集中在 `app/src/main/kotlin/com/koubeiradar/app/ui/theme/Theme.kt`，别在组件里散落硬编码颜色
- 颜色优先走 `MaterialTheme.colorScheme.*`，语义色走 `MaterialTheme.koubeiColors.*`
- 新增颜色先加到色阶表，再引用 token 名，不要直接写 hex
- 布局用 Compose 原生，不引入新依赖

## 文档

- `README.md` — 功能、构建、验证状态（含「尚未真机联调」的如实说明）
- `docs/RESEARCH.md` — 数据源调研
- `docs/DESIGN.md` — 评分算法与架构
- `../whale_style_assets/whale_style_guide.md` — 视觉规范与素材索引（项目外，只读参考）
