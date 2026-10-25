# GitHub 现有项目调研

调研日期：2026-10-23。

> **方法与局限**：沙箱里 GitHub 搜索 API 被策略拦截（只放行与会话绑定仓库相关的路径），所以改用「网页搜索 + 抓取 github.com 仓库页」。
> 表中星标/协议/更新信息是抓取当时页面上显示的值；标注"（搜索摘要）"的条目没有打开仓库页逐项核实。

## 1. 结论先行

1. **没有现成的开源项目**能做"按口碑自动筛选外卖门店"的 Android App。相关项目分散在四类：爬虫、情感语料、Android 外卖/餐厅 UI 样例、手机智能体。
2. **外卖平台没有面向个人开发者的店铺评分开放接口**。GitHub 上的美团/点评爬虫依赖逆向与反反爬，作者自己声明"仅限学习、禁止商用"，不适合做产品底座。
3. **智能体确实能把事情变简单**，但要用在对的位置：
   - 取数据：让用户自己浏览，智能体读截图/文本（多模态抽取）；或让手机智能体（Open-AutoGLM 类）替用户翻页——都不需要逆向。
   - 判口碑：LLM 直接读评价找雷点，比训练情感模型省事得多。
   - 理解需求：一句话 → 筛选条件。
   - 但**打分与排序必须是确定性的、可解释的**，LLM 只做外壳。
4. 数据源用**高德或百度的官方 POI 接口**（二选一，默认高德：评分与人均；百度另有评价数 `comment_num`），外卖特有数据（月售、配送、评价原文）靠"导入"通道补。

## 2. 项目清单

### 2.1 外卖 / 点评数据类

| 项目 | 说明 | 结论 |
|---|---|---|
| [Sniper970119/dianping_spider](https://github.com/Sniper970119/dianping_spider) | 大众点评爬虫：搜索页/详情页/评论页，输出到 MongoDB。1.3k★，**GPL-3.0**。README：「仅限学习交流使用，禁止商用」；作者称"可能没时间维护" | 思路可参考，**不采用**（条款、维护、反爬） |
| 美团外卖爬虫 | 未找到持续维护的消费者侧项目；检索到的多是教程、商家侧 SDK（如 Packagist `izjw/take-out`、Clojars `huzhengquan/waimai`，均为商家开发工具包）（搜索摘要） | 无可用底座 |
| 外卖红包类脚本 | 例如 Gitee 镜像 `lvsp/eleme` 的 README 自称"饿了么、美团均已失效"（搜索摘要） | 说明平台侧会持续对抗，爬取路线不稳 |
| 美团联盟 SDK（第三方） | 暴露订单查询、取链、商品列表等；另有分销联盟 SDK 说明"到店商品搜索接口美团已停止该接口请求权限"（搜索摘要）。**未发现店铺评分接口** | 不适用 |
| [SophonPlus/ChineseNlpCorpus](https://github.com/SophonPlus/ChineseNlpCorpus) | 中文 NLP 语料集合，6.6k★。其中 **waimai_10k**：某外卖平台用户评价，正向约 4000 条、负向约 8000 条 | 用作评价词典的构造依据与测试语料；LLM 方案下不必再训练模型 |

### 2.2 Android 外卖 / 餐厅样例

| 项目 | 说明 | 结论 |
|---|---|---|
| [vipulasri/JetDelivery](https://github.com/vipulasri/JetDelivery) | Compose 外卖示例，431★，Apache-2.0；展示 server-driven UI | 只有界面，无口碑逻辑；UI 组织可参考 |
| Restaurants-Compose / LittleLemon / RestaurantFinderApp（搜索摘要） | Compose 列表+筛选/排序、Ktor 拉静态 JSON、Maps+Places 的餐厅查找 | 小体量学习项目 |
| Papara-Group-Project「Chat Chef」（搜索摘要） | Compose + Hilt + Room + Retrofit，接 ChatGPT 做餐厅推荐 | 说明"LLM 做推荐"可行，但没有口碑评分 |
| s-4-m-a-n/RestaurantBot（搜索摘要） | Rasa 点餐机器人，自述"与热门外卖服务集成"未完成 | 对话式点餐原型 |

### 2.3 手机智能体 / GUI Agent

| 项目 | 控制方式 | 许可 / 星标 | 与外卖的关系 |
|---|---|---|---|
| [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | **ADB**，截图 → VLM 决策 → 点击；Android 7.0+；需要 OpenAI 兼容端点（自部署 AutoGLM-Phone-9B，或智谱 BigModel / ModelScope） | Apache-2.0，26.5k★ | README 应用表**列出美团、饿了么、大众点评**；有敏感操作确认与人工接管（登录/验证码/支付）；声明"仅供研究和学习" |
| [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent) | 通义实验室 GUI Agent 家族（Mobile-Agent-v3.5、GUI-Owl-1.5 模型 2B–235B） | MIT，9.3k★ | 通用手机/PC 操作 |
| [droidrun/droidrun](https://github.com/droidrun/droidrun)（现名 Mobilerun） | **ADB 或 Portal 应用（无障碍服务）**；支持 OpenAI/Anthropic/Gemini/DeepSeek/Ollama 等 | MIT，9.7k★ | 通用；"端侧无障碍 + LLM"路线的参考 |
| [TencentQQGYLab/AppAgent](https://github.com/TencentQQGYLab/AppAgent) | ADB；探索（生成文档）+ 部署两阶段；GPT-4V / qwen-vl-max | MIT，6.9k★ | 通用；最近更新条目 2025.3 |
| [Core-Mate/OpenGUI](https://github.com/Core-Mate/OpenGUI) | Android 客户端（无障碍服务 + 悬浮窗）+ 自托管后端；接 Claude/GPT/Gemini/Kimi 等 | **BUSL-1.1（非 OSI 开源）**，1.8k★ | 通用；商用需另行授权 |

### 2.4 Kotlin / Android 端 Agent 框架

| 项目 | 说明 | 取舍 |
|---|---|---|
| [JetBrains/koog](https://github.com/JetBrains/koog) | Kotlin 多平台 Agent 框架，Apache-2.0，4.6k★；支持 OpenAI/Anthropic/Google/DeepSeek/OpenRouter/Ollama/Bedrock，MCP；README 要求较新的 Kotlin | **本项目不用**：自写的循环只有几百行，却要精确控制 Claude 的 thinking 块回放、终止工具协议、可脚本化的假 LLM 单测；框架会带来额外的版本耦合 |
| Anthropic 官方 Java SDK | Kotlin 项目按官方文档走 Java SDK（`com.anthropic:anthropic-java`，本次使用 3.2.0） | **采用**（Claude 路径） |

### 2.5 官方数据源

| 来源 | 核实结果 | 取舍 |
|---|---|---|
| [高德 Web 服务·搜索 POI 2.0](https://lbs.amap.com/api/webservice/guide/api-advanced/newpoisearch) | `GET /v5/place/around`；`types=050000`（餐饮服务）；`show_fields=business` 返回 `rating`、`cost`（仅餐饮/酒店/景点/影院）、`tag`、营业时间等；`page_size` ≤ 25；同条件最多取 200 条 | **默认数据源**。缺评价数/月售/配送信息 |
| [高德 MCP Server](https://lbs.amap.com/api/mcp-server/summary) | 官方存在（列出 17 项能力：关键词搜索、周边搜索、详情等）；文档页未写明接入 URL，也未提 rating/cost | 本项目在 App 内直接调 REST，不引入 MCP |
| [百度地图 Web 服务·地点检索](https://lbsyun.baidu.com/faq/api?title=webapi/guide/webservice-placeapiV3/interfaceDocumentV3)（2026-10-25 复核） | 官方现行的 `GET /place/v3/around`（圆形区域检索）：`query`（如 `美食`）、`type`（二次筛选）、`location=纬度,经度`、`radius`、`radius_limit`、`scope=2`、`filter`、`coord_type=2` 输入 GCJ-02、`ret_coordtype=gcj02ll` 返回 GCJ-02（默认 BD-09）、`page_size` 10–20、`page_num` 从 0 起、`total` ≤ 150；返回 `overall_rating`（评分）、`comment_num`（**评价数**）、`price`、`shop_hours`、`classified_poi_tag`，均为字符串；状态码表见[附录](https://lbsyun.baidu.com/index.php?title=webapi/appendix)；地理编码 `/geocoding/v3/`。官方页没有示例响应、对 `detail_info` 的嵌套没有说明。2.0 的圆形检索页在沙箱里只会重定向到首页，所以不用 2.0 | **已接入**（第二数据源），逐项核实状态与未验证部分见 [DESIGN.md §3.4](DESIGN.md) |
| 腾讯位置服务 | POI 数据没有评分字段（调研结论，本次未重新抓取其文档） | 不接入 |

## 3. 方案取舍

| 路线 | 稳定性 | 合规 | 数据含量 | 工程量 | 结论 |
|---|---|---|---|---|---|
| A. 逆向/爬取美团饿了么（GitHub 现有爬虫） | 低（反爬、签名、字体加密） | **差** | 高 | 高且持续 | ✗ |
| B. 高德 / 百度 POI 官方接口 | 高 | 好 | 中（有评分/人均；百度另有评价数；都无配送） | 低 | ✓ 默认（二选一） |
| C. 用户导入（截图/文本）+ 多模态 LLM 抽取 | 高 | 好（用户自己浏览） | 高（月售、配送、评价原文） | 低（一个提取器） | ✓ 补充 |
| D. GUI 智能体（Open-AutoGLM 等）替用户翻页 | 中 | 灰（平台条款、需人工接管） | 高 | 中（环境搭建） | 可选桥接，见 [GUI_AGENT_BRIDGE.md](GUI_AGENT_BRIDGE.md) |
| E. 平台联盟 API | — | 好 | 无店铺评分 | — | ✗ |

最终：**B 打底（高德或百度），C 补强，D 留作可选桥接；打分内核确定性，智能体做外壳。**详见 [DESIGN.md](DESIGN.md)。

## 4. 评分方法的依据

- **贝叶斯平均**（向先验均值收缩，评价越少越向先验靠拢）：解决"3 条评价的 5.0 分"压过"3000 条评价的 4.7 分"的问题——IMDb 加权评分同类思路。
- **相对位置（百分位）**：平台评分普遍虚高，只看绝对值没有区分度。
- **置信度** `1 − exp(−n/n0)`：评价量越大越可信，且边际递减。
- **评价文本红线词**：卫生/食安类投诉的影响远大于普通差评。
- 以上是工程启发式，不是经过平台真实标注数据校准的模型；`ScoringConfig` 暴露了全部参数，并提供三种预设。
