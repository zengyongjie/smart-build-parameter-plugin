# Smart Build Parameter Plugin 需求文档

- **版本**: v0.1（草案，供评审）
- **日期**: 2026-11-03
- **适用范围**: 第一期（Phase 1）
- **配套文档**: [测试验证文档](./TEST_PLAN.md)

---

## 1. 背景与目标

Jenkins 原生参数类型（String / Choice / Boolean Parameter 等）在**交互能力**（条件显隐、多选、可过滤下拉）、**数据来源**（Shell / HTTP 第三方系统）与**展示体验**（表单组织与现代化）上存在局限。成熟插件（如 Active Choices、Extended Choice Parameter）能覆盖部分场景，但普遍存在维护状态参差、与声明式 Pipeline 集成不佳、UI 老旧等问题。

本插件提供一组面向**声明式 Pipeline（第一期）**的"智能参数"类型，目标：

1. 补齐原生参数不具备的交互与数据源能力（需求一 ~ 需求四）；
2. 对 Build With Parameters 页面与构建历史参数展示页做现代化组织与美化（需求五 + 补充要求 3）；
3. **总原则：只增强、不破坏** —— 不得影响 Jenkins 原有框架、原有参数类型、既有任务与页面功能。

## 2. 范围

### 2.1 第一期范围内

| 能力 | 需求来源 |
|---|---|
| 条件显隐布尔参数（勾选展示关联参数、取消隐藏） | 需求一 |
| 多选复选框参数 | 需求二 |
| Shell 命令动态取值的可过滤下拉（单选/多选） | 需求三 |
| HTTP 请求动态取值的可过滤下拉（单选/多选，支持认证） | 需求四 |
| Build With Parameters 页面现代化 | 需求五 |
| 构建历史 Parameters 页展示新类型参数并现代化 | 补充要求 3 |
| 声明式 Pipeline `parameters {}` 指令直接使用全部新类型 | 补充要求 4 |

### 2.2 第一期范围外（列入二期候选）

- 脚本式 Pipeline 的**官方适配与验证**（新类型底层与 Pipeline 范式无关，理论上脚本式同样可用，但一期只对声明式做适配与测试承诺，见 §8）；
- 下拉框远程搜索（服务端过滤）、拼音/模糊搜索；
- 多级级联联动（参数 A 的选择决定参数 B 的候选值）；
- 全局管理配置（Manage Jenkins 级别的统一策略：HTTP URL 白名单、样式开关等）；
- Shell 命令的脚本审批/沙箱机制（一期以权限模型 + 文档约束，见 NFR-2）；
- 隐藏参数"仍然提交"的可配置策略（一期固定为不提交，见 §9 决策 D1）。

## 3. 术语

| 术语 | 含义 |
|---|---|
| 参数定义（ParameterDefinition） | Jenkins 中一个参数的类型、名称、默认值、表单渲染方式的定义 |
| Build With Parameters 页面 | 用户点击"Build with Parameters"后看到的参数填写表单页 |
| ParametersAction | 一次构建实际提交的参数值集合，构建历史中"Parameters"链接对应页面 |
| 声明式 Pipeline | Jenkinsfile 中 `pipeline { }` 结构的流水线定义方式 |
| @Symbol | 插件为参数类型注册的 Pipeline 可用符号名（如 `smartBoolean`） |
| 数据源（Source） | 动态下拉参数候选值的来源（Shell / HTTP） |

## 4. 需求框架与追溯矩阵

需求分为四层：

```
┌─ 交互层  FR-1 条件显隐（依赖参数间关系）
├─ 类型层  FR-2 多选复选框   FR-3 Shell 下拉   FR-4 HTTP 下拉
├─ 呈现层  FR-5 Build With Parameters 现代化   FR-6 构建历史参数展示现代化
└─ 保障层  NFR-1~5 + 回归红线（不破坏原有功能）
```

| 原始需求描述 | 对应编号 |
|---|---|
| 需求一：paramsA 勾选展示关联参数 / 取消隐藏 | FR-1 |
| 需求二：复选框参数，支持多选 | FR-2 |
| 需求三：Shell 取值下拉，可过滤，单选/多选，高级按钮配置，可用在 Pipeline | FR-3 |
| 需求四：HTTP 取值下拉（Basic / Bearer 认证），可过滤，单选/多选 | FR-4 |
| 需求五：Build With Parameters 现代化组织与美化 | FR-5 |
| 补充 3：构建历史 Parameters 页展示新类型并现代化 | FR-6 |
| 补充 2：不破坏 Jenkins 原有框架与功能 | NFR-3、§7、测试文档回归章节 |
| 补充 4：第一期仅支持声明式 Pipeline | §2.2、§8 |

## 5. 功能需求

### FR-1 条件显隐布尔参数（`smartBoolean`）

一个复选框型参数，控制**其他已定义参数**在 Build With Parameters 页面上的显示与隐藏。

**配置项**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `name` | string | 必填 | 参数名 |
| `description` | string | 空 | 描述 |
| `defaultValue` | boolean | `false` | 表单初始勾选状态 |
| `controls` | string | 空 | 受控参数名列表（逗号分隔），指向同一 Job 中已存在的其他参数 |

**行为规范**

1. 表单渲染时，`defaultValue=true` → 受控参数显示；否则隐藏。
2. 用户勾选/取消复选框时，**无需刷新页面**，受控参数块即时显示/隐藏（前端行为）。
3. **提交语义（决策 D1）**：被隐藏的参数**不随构建提交**——即不出现在本次构建的 `ParametersAction` 与 `params` 中；取消勾选后参数回到表单，恢复提交。
   实现语义：隐藏 = 前端对参数块设置 core 的 `field-disabled` 属性（结构化表单序列化时整块排除），不做服务端过滤——
   因此 Rebuild/重放等非表单路径天然按记录值执行，与第 5 条一致。隐藏的视觉范围是**整个表单行**
   （`div[name="parameter"]` 参数块连同其外层 `f:entry` 行/标题/描述一起 `display:none`）——只隐藏参数块会
   在页面上留下一个空标题行（2026-11-04 用户反馈修复）。
4. 参数本身的值语义与原生 Boolean Parameter 一致（`BooleanParameterValue`），在 Pipeline 中 `params.name == true/false`。
5. Rebuild / Replay 场景：以历史构建参数重放时，按重放提交的参数集合执行；被隐藏参数若在重放集合中存在，按记录值提交（不做二次过滤，避免与用户预期冲突）。
6. **隐藏参数豁免校验（增补 B）**：被隐藏的受控参数不参与表单校验（如 `smartCheckbox` 的 `minSelected`、必填约束）——隐藏状态不得阻止表单提交。

**边界与异常**

- `controls` 引用了不存在的参数名：表单正常渲染，忽略缺失项，并在配置页给出提示（配置保存时校验，见验收 6）。
- 循环/自引用（A 控制 B、B 控制 A）：一期按声明顺序渲染，不做递归联动；文档明确不建议。
- 与原生参数混用：受控参数可以是**任意类型**（原生 String/Choice 或本插件新类型）。

**验收标准**

1. 勾选后关联参数可见，取消后不可见（DOM 层面被隐藏，且不提交）；
2. 隐藏状态下触发构建，构建的 Parameters 页与 `params` 中**没有**被隐藏参数；
3. 勾选状态下触发构建，被控参数正常提交；
4. 声明式 Pipeline `parameters { smartBoolean(...) }` 可定义并生效；
5. 同一页面与原生 Boolean Parameter 共存，互不影响；
6. `controls` 含无效名称时保存配置有明确提示，构建表单不受影响；
7. 被隐藏的带 `minSelected` 约束的参数不阻止表单提交（TC-FR1-008）。

### FR-2 多选复选框参数（`smartCheckbox`）

**配置项**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `name` / `description` | string | — | 同上 |
| `options` | 多行文本 | 必填 | 每行一个选项 |
| `defaultChecked` | string | 空 | 预勾选的选项（逗号分隔） |
| `delimiter` | string | `,` | 多选值的拼接分隔符 |
| `minSelected` | int | 0 | 最少勾选数（0 = 不限制） |

**行为规范**

1. 表单以复选框组渲染全部选项，可多选；
2. 提交值 = 勾选项按提交顺序用 `delimiter` 拼接的字符串（Pipeline 参数值统一为字符串语义，见决策 D2）。
   实现语义：浏览器表单按选项**列表顺序**提交（与常规多选控件一致）；结构化表单/远程 API 按传入 **JSON 数组顺序**；
   远程 API 亦可直接传预拼接字符串（`build -p NAME=a,b`）。注意 `buildWithParameters` 查询串方式对**所有**参数类型
   都不接受重复键（Jenkins core 限制），多选请用上述两种方式；
3. `minSelected > 0` 且勾选数不足时，前端阻止提交并提示；
4. 未勾选任何项时提交空字符串；
5. **分隔符冲突校验（增补 A）**：任一选项值不得包含 `delimiter` 字符；`options` 中的值不得重复；`defaultChecked` 中的值必须存在于选项列表。违反时配置表单以内联错误/警告提示（保存前校验），避免拼接值产生歧义。该校验同样适用于 FR-3 / FR-4 多选模式下显式指定的 `delimiter`。

**验收标准**

1. 多选提交后，构建 Parameters 页与 `params.name` 得到按序拼接值（如 `dev,test`）；
2. `defaultChecked` 在表单初始即勾选；
3. `minSelected` 生效且提示可读；
4. config roundtrip（配置保存后回读一致）；
5. 选项值包含分隔符 / 选项重复 / `defaultChecked` 含无效值时，配置校验给出明确提示；
6. 与 FR-1 组合：作为受控参数可被正常显隐，且被隐藏时其 `minSelected` 校验不阻止表单提交。

### FR-3 Shell 动态下拉参数（`smartSelect`，source=shell）

候选值由执行 Shell 命令得到：命令输出**每行一个选项**（去除空行与行尾空白）。

**配置项**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `name` / `description` | — | — | 同上 |
| `selectMode` | `single` / `multiple` | `single` | 单选 / 多选 |
| `filterable` | boolean | `true` | 下拉是否支持输入过滤 |
| `defaultValue` | string | 空 | 默认选中值（多选时逗号分隔） |
| `delimiter` | string | `,` | 多选拼接分隔符 |
| `source.command` | 多行文本 | 必填 | Shell 命令（在**高级设置**折叠区配置） |
| `source.timeoutSeconds` | int | 5 | 命令超时 |
| `source.cacheTtlSeconds` | int | 0 | 结果缓存时长（0 = 每次表单渲染都执行） |
| `source.maxOutputBytes` | int | 65536 | 输出截断上限（防御性） |

**行为规范**

1. Shell 命令在**高级设置（Advanced）折叠区**配置（点开才展示，降低配置噪音）；
2. 命令在 Jenkins 控制器节点以 `/bin/sh -c` 执行（执行位置与权限含义见 NFR-2）；
3. 下拉组件：**常规单行下拉**（触发按钮单行展示已选拼接值，超长省略号截断）；点击展开选项列表；开启 `filterable` 时顶部有过滤输入框，**输入即过滤**（大小写不敏感子串匹配，纯前端）；点击组件外部或按 Esc 收起列表；
   - 单选模式：列表为 radio 语义，选中即收起并回填；
   - 多选模式：列表为 checkbox 语义，**勾选即时生效**（触发按钮实时回显拼接值），无"确认"按钮（2026-11-04 用户反馈修订：多选为常规单行下拉多选框，不做面板内确认按钮）；
4. 异常呈现（不白屏、不 500）：命令非零退出 / 超时 / 输出超限 → 表单内联展示错误信息与"重试"入口，参数项不可用于提交；
5. `cacheTtlSeconds > 0` 时，缓存命令输出（线程安全），TTL 内的多次表单渲染不重复执行命令。

**验收标准**

1. 命令输出行正确映射为选项（含空行过滤）；
2. 过滤输入实时缩小候选列表；
3. 单选/多选两种模式均可用，多选值按序拼接（决策 D2）；
4. 超时（如 `sleep 30` 配 timeout=2）与失败（`exit 1`）场景呈现内联错误，页面其他功能正常；
5. 缓存生效（同 TTL 内两次渲染仅执行一次命令，测试以标记文件计数）；
6. 声明式 Pipeline `parameters { smartSelect(...) }` 可定义并生效；
7. 配置在高级折叠区，未展开时不占版面。

实现语义（与 FR-2 对齐）：
- 选项在**表单渲染时**于服务端求值（`cacheTtlSeconds > 0` 时命中源缓存），渲染结果嵌入页面；
  "重试"按钮经插件自有端点 `/smart-build-parameter/reloadOptions?job=<全名>&name=<参数名>`
  **绕过缓存**强制重跑，以 JSON 返回 `{options:[…]}` 或 `{error:…}`；
- 提交契约同 FR-2：单选 = radio 提交字符串；多选 = 带 `json` 属性的 checkbox（重复 `value` → JSONArray，按列表顺序拼接）；
- 错误态**不渲染任何 `value` 控件**（而非禁用控件）——结构化表单因此完全不提交该参数（与 D1 同构），
  Pipeline 中 `params.X` 回退到配置的默认值；stderr 首行（≤2KB）与退出码并入错误消息；
- 缓存仅缓存**成功**结果（失败后"重试"必然重跑）；并发渲染经实例级同步保证单次执行；
- 超限（stdout > `maxOutputBytes`）与超时都会 `destroyForcibly`，不会泄漏进程；stderr 始终后台排空，避免管道写满阻塞。

### FR-4 HTTP 动态下拉参数（`smartSelect`，source=http）

候选值由 HTTP(S) 请求第三方系统获得，与 FR-3 共用同一下拉组件与 `selectMode/filterable/delimiter` 语义。

**配置项（差异部分）**

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `source.url` | string | 必填 | 请求地址（支持 `${PARAM}` 引用同表单其他参数的值，二期；一期静态 URL） |
| `source.method` | GET | GET | 一期仅 GET |
| `source.timeoutSeconds` | int | 10 | 请求超时 |
| `source.cacheTtlSeconds` | int | 0 | 同 FR-3 |
| `source.authType` | `none` / `basic` / `bearer` | `none` | 认证方式 |
| `source.credentialsId` | credential 选择器 | 空 | basic → 用户名密码凭证；bearer → Secret text 凭证（发送为 `Authorization: Bearer ***`） |
| `source.responseType` | `jsonArray` / `jsonPath` / `textLines` | `jsonArray` | 响应解析方式 |
| `source.jsonPath` | string | 空 | `jsonPath` 模式下的点路径，如 `data.list[*].name`（简化语法，支持数组展开一层） |

**行为规范**

1. **认证一律通过 Jenkins 凭证库**（决策 D3）：不在参数配置中保存明文用户名/密码/token；凭证选择器按 `authType` 过滤类型；
2. 解析规则：
   - `jsonArray`：响应为字符串 JSON 数组（`["a","b"]`），或对象数组中每项为纯标量；
   - `jsonPath`：按点路径取数组（一层 `[*]` 展开）；
   - `textLines`：响应体每行一个选项；
3. TLS 默认校验证书（跟随 Jenkins JVM 全局配置）；超时、非 2xx、解析失败 → 同 FR-3 的内联错误呈现；
4. 响应体大小上限与 FR-3 `maxOutputBytes` 同语义。

**验收标准**

1. JSON 数组 / 点路径 / 文本行三种解析模式均正确产出选项；
2. Basic 认证正确发送 `Authorization: Basic ...`（以测试桩断言请求头）；Bearer 正确发送 `Authorization: Bearer <secret>`；
3. 凭证值**不会**出现在任何页面、日志、配置回显中；
4. 401/403/超时/非法 JSON 场景呈现内联错误，页面其余部分正常；
5. 过滤、单选/多选、拼接语义与 FR-3 一致；
6. 声明式 Pipeline 可定义并生效。

实现语义（与 FR-3 对齐）：
- `HttpSelectSource` 与 `ShellSelectSource` 共用基类 `SmartSelectSource`（TTL 缓存、`fetch(forceRefresh)`、
  选项清洗），符号 `http`，在 job-config 高级区与 shell 来源并列（dropdownDescriptorSelector）；
  构建页渲染、重试端点、错误态不渲染 `value` 控件、多选拼接语义**全部复用 FR-3**；
- 请求用 JDK 内置 `java.net.http.HttpClient`（连接超时 = 请求超时 = `timeoutSeconds`，默认 10），
  每次 fetch 新建 client；TLS 跟随控制器 JVM 默认校验；
- 认证（D3）：构建页渲染在无用户会话上下文执行，凭证以 `ACL.SYSTEM2` 系统身份查询
  （`CredentialsProvider.lookupCredentialsInItemGroup`）；basic → 用户名密码凭证
  （`Authorization: Basic base64(user:pass)`），bearer → plain-credentials 的 Secret text 凭证
  （`Authorization: Bearer <secret>`）。凭证 ID 不存在或类型不匹配 → 结构化错误，
  消息只含凭证 **ID**，绝不包含 secret（错误消息、页面、日志均验证）；凭证下拉
  （`doFillCredentialsIdItems`）同时列出两类凭证，类型在 fetch 时校验；
- 解析：`jsonArray`（顶层标量数组；嵌套对象/数组报错）／`jsonPath`（点路径 + 一层 `[*]` 展开；
  普通段遇数组提示改写为 `key[*]`，`[*]` 段遇非数组、路径落空、路径为空均为结构化错误）／
  `textLines`（每行一个选项）；结果统一经 `cleanOptions`（去空白、去重、保序）；
- 防御：响应体读到 `maxOutputBytes` 上限即中止报错（非静默截断）；非 2xx 先排空连接再报状态码；
  连接失败按异常类型归类，消息不含 URL 之外的内部细节；
- Pipeline：`smartSelect(name:'BR', source: http(url:'…', responseType:'textLines'))`。

### FR-5 Build With Parameters 页面现代化

**行为规范**

1. 对 Build With Parameters 页的参数表单做现代化组织：卡片式分组（按参数定义顺序分区）、统一的标签/描述/控件排版、合理的间距与字体层级、清晰的主/次按钮（Build 置为主按钮）、focus/hover 状态；
2. 新类型参数的组件（开关、复选框组、过滤下拉、多值 chips）与整体风格统一；
3. **实现约束（不破坏红线）**：
   - 以**渐进增强**方式注入本插件自有的 scoped CSS/JS 资源，**不覆盖任何 Jenkins 核心模板/资源文件**；
   - 本插件 JS 加载失败或被禁用时，页面自动回退为原生表单，功能完整可用；
   - 原生参数类型（及页面上其他插件参数）渲染不受影响；
4. 插件提供"启用现代化样式"开关（默认开），出问题可一键关闭回到原生样式（全局配置，一期为系统属性或简单全局配置项）。

**验收标准**

1. 安装插件后 Build With Parameters 页面出现现代化布局（DOM 有可识别的样式钩子/结构）；
2. 同页混排原生 String/Choice/Boolean 与全部新类型，均可正常填写与提交；
3. 关闭样式开关后页面与未安装插件时一致（截图/DOM 对比）；
4. JS 禁用（或资源 404 模拟）时表单仍可提交成功。

实现语义：
- 注入点为插件自有 `PageDecorator`（`SmartBuildParameterPageDecorator`）的 `header.jelly`——
  Jenkins 会对每个页面包含所有 PageDecorator 的 header 视图，因此**不覆盖任何核心模板**；
  注入条件在服务端判定：请求 URI 以 `/build` 结尾（即 Build With Parameters 表单页），
  其余页面零注入（job 首页等实测无插件资源）；
- 注入物只有两份自有资源：`css/smart-build-page.css` + `js/smart-build-page.js`（defer）；
  JS 只做**加类**（`form.sbpd-modern`、`div[name=parameter].sbpd-card`、按钮栏 `sbpd-actions`），
  不移动/包裹/删除任何节点——与参数脚本（smart-params.js、smart-select.js、核心
  hudson-behavior.js）零冲突；CSS 全部选择器 scoped 在 `.sbpd-modern` 之下，JS 不跑即无任何效果；
  `sbpd-actions` 挂在**表单最后一个非 smartSelect 内部的 primary 按钮**（核心 Build/Cancel 栏）的
  父节点上，并保持**常规文档流**（上边框分隔）——参数行显隐时按钮栏自动随页面重排，不会被遮挡，
  也绝不把动作条样式错挂到下拉面板上（2026-11-04 用户反馈修复）；
- 开关（行为规范 4，一期系统属性）：`-Dio.github.xvvx.smartbuildparameter.modernBuildPage=false`
  ——属性**每次渲染时读取**，script console 可热切换；关闭时连 CSS/JS 都不注入（不是仅不生效）；
- "JS 禁用"语义澄清：核心参数表单的提交本身依赖核心自带 hudson-behavior.js 的
  buildFormTree（浏览器完全禁 JS 时核心原生参数页也无法提交——核心行为，非本插件引入）；
  本插件的验收含义是**插件资源 404 / 开关关闭时**表单回到核心原生行为并可正常提交
  （含多选 checkbox 的 json 属性拼接——该契约本来就由核心 buildFormTree 实现）。

### FR-6 构建历史 Parameters 展示页现代化

**行为规范**

1. 构建详情页侧栏 "Parameters" 页能正确展示**全部新类型参数**的构建时取值：
   - `smartBoolean`：布尔徽章样式；
   - `smartCheckbox` / 多选 `smartSelect`：以 chips（标签片）逐值展示，同时给出原始拼接串；
   - 超长值：截断显示 + 展开/悬浮完整值；
2. 展示风格与 FR-5 一致；原生参数类型仍按原生方式展示，不受影响；
3. 数据来源为该次构建的 `ParametersAction`（构建重放/重跑场景取值正确）。

**验收标准**

1. 带全部新类型参数的构建完成后，Parameters 页各项取值与提交值一致；
2. 多选值 chips 化展示且含原始串；
3. 原生参数在安装插件前后展示不变（回归）；
4. API 侧（`.../<build>/api/json`）参数数据结构保持 Jenkins 惯例（不影响既有消费方）。

实现语义：
- **值子类 + 视图按运行时类型解析**：core 的 `ParametersAction/index.jelly` 对每个参数值
  `st:include it="${parameterValue}" page="value.jelly"`——视图按值的**运行时类型**查找。插件定义
  `SmartBooleanParameterValue extends BooleanParameterValue`、
  `SmartMultiParameterValue extends StringParameterValue`（smartCheckbox 与多选 smartSelect 共用：
  继承的 `value` = 拼接串 + 自有 `choices` = 有序选择列表），子类命中插件自有 `value.jelly`，
  原生类型照走核心视图（验收 3 天然满足，零核心模板覆盖）；
- **展示**：布尔徽章 `span.sbpd-badge--true/false`；多值 chips `span.sbpd-chip`（每值一枚）+
  原始拼接串 `code.sbpd-raw`（D2 原样字符串）；**超长截断**为纯 CSS（`text-overflow: ellipsis` +
  `max-width`），完整值始终保留在 `title` 属性（悬浮展开）；空选择渲染 `(empty)` 标记（i18n）；
  样式在自有 `css/smart-values.css`，由 value.jelly 自行引入，全部选择器 scoped 在 `.sbpd-values`
  之下——样式资源 404 时退化为纯文本，仍可读；
- **取值来源与重放**（行为规范 3）：`choices` 随 ParametersAction 持久化进 build.xml（XStream；
  字段用 `ArrayList` 而非 `List.of()` 等不可变集合，规避 XStream 无法实例化的问题），
  Replay/重跑按记录值渲染；`createValue(String)`（CLI/远程 API 的预拼接串路径）按 delimiter 重拆
  （`Pattern.quote` 转义，增补 A 保证选项不含 delimiter、拆分无歧义）。结构化表单路径
  `createValue(req, jo)` 的 `value` 为**字符串**时（API 直接 POST `json={parameter:[{name,value:"a,b"}]}`
  或浏览器单选提交）走同一重拆逻辑——曾发现 Bug：整串被当作单个 choice 渲染成一枚巨型 chip，
  全场景矩阵仿真（2026-11-04）暴露并修复，见 TEST_PLAN §5.1；
- **API 惯例**（验收 4）：`choices` 仅用于展示、不参与远程导出（非 `@Exported`），
  `<build>/api/json` 的 parameters 保持 `name`/`value` 形状（布尔导出 JSON true/false、多选拼接串原样）；
  equals/hashCode 在继承 `StringParameterValue` 语义之上叠加 `choices`（SpotBugs 一致性要求）。

## 6. 非功能需求

| 编号 | 类别 | 要求 |
|---|---|---|
| NFR-1 | 性能 | 表单渲染（不含外部数据源耗时）P95 < 2s；Shell/HTTP 源必须有超时；提供可选缓存；缓存实现线程安全 |
| NFR-2 | 安全 | ① 凭证仅存于 Jenkins 凭证库，配置/页面/日志不回显明文；② Shell 源命令由具备 Job/Configure 权限者配置、在控制器执行，风险与 Active Choices 同类，文档明示（二期考虑脚本审批）；③ 选项值渲染一律 HTML 转义防 XSS；④ HTTP 源存在 SSRF 面，一期在文档与管理员须知中明示，二期提供 URL 白名单 |
| NFR-3 | 兼容性 | ① Jenkins 基线 2.528.x LTS（JDK 17+，编译目标 17）；② 不覆盖任何核心资源/模板；③ 安装、启用、禁用、卸载插件不破坏既有 Job 配置（卸载后新类型参数定义残留时降级为不可识别定义，不影响其他参数与构建）；④ 与声明式 Pipeline `parameters` 指令、Replay、Rebuild 兼容 |
| NFR-4 | 质量 | SpotBugs 零告警（随构建强制）；核心逻辑单元测试覆盖；JenkinsRule 集成测试覆盖表单与提交链路 |
| NFR-5 | 国际化 | UI 文案资源化（`config.jelly.properties` 中英双语），一期提供 zh_CN 与英文 |

## 7. "不破坏"红线清单（回归验收基准）

1. 原生 String / Choice / Boolean / Text 参数在安装插件前后行为一致（配置、渲染、提交、历史展示）；
2. 未使用新类型参数的既有 Job 升级安装插件后配置无 diff、构建无差异；
3. 禁用/卸载插件后，Jenkins 与既有 Job 完全正常；
4. 声明式 Pipeline（不使用新参数）语法、执行、Replay 不受影响；
5. Build With Parameters 页上第三方插件参数（若装有其依赖）不被本插件样式/脚本破坏（一期验证清单覆盖页面级共存冒烟）。

## 8. 分期规划

| 阶段 | 内容 |
|---|---|
| Phase 1（本文档） | FR-1 ~ FR-6 全部；声明式 Pipeline 适配与验证；本机 Docker Jenkins 端到端验证 |
| Phase 2 候选 | 脚本式 Pipeline 官方支持与验证；URL 白名单与脚本审批；远程搜索/模糊匹配；级联联动；隐藏参数提交策略可配；全局管理配置 |

> 说明：新参数类型的底层实现与 Pipeline 范式无关，脚本式 Pipeline 预计可直接使用；但一期仅对声明式做**适配、文档与测试承诺**（补充要求 4）。

## 9. 决策记录（默认决策，可推翻）

| 编号 | 决策 | 理由 |
|---|---|---|
| D1 | 被隐藏的受控参数**不随构建提交** | 与"无需展示"直觉一致；避免隐藏但生效造成误导。若需"隐藏仍提交默认值"，二期做成可配 |
| D2 | 多选值统一为**按勾选顺序、可配分隔符（默认 `,`）拼接的字符串** | Pipeline `params` 生态以字符串为主，保证声明式/脚本式与历史展示一致性 |
| D3 | HTTP 认证一律走 **Jenkins 凭证库**（basic=用户名密码凭证，bearer=Secret text） | 安全合规（NFR-2），避免明文凭据进入 Job config.xml |
| D4 | 下拉过滤为**前端子串匹配**（大小写不敏感） | 简单可靠；拼音/远程搜索列入二期 |
| D5 | Shell 源命令在**控制器**执行 | 表单渲染发生在控制器侧；风险与权限边界在 NFR-2 明示 |
| D6 | 现代化 UI 为**渐进增强**，不覆盖核心模板 | 落实"只增强、不破坏"红线 |

## 10. 附录

### 10.1 Pipeline 符号速查（一期）

```groovy
parameters {
  smartBoolean(name: 'DEPLOY_DB', defaultValue: false, controls: 'DB_HOST,DB_PORT', description: '部署数据库')
  smartCheckbox(name: 'ENV', options: 'dev\ntest\nprod', defaultChecked: 'dev', delimiter: ',', description: '环境(多选)')
  smartSelect(name: 'BRANCH', selectMode: 'single', filterable: true, description: '分支',
      source: [type: 'shell', command: 'git ls-remote --heads https://github.com/jenkinsci/jenkins.git | cut -f2', timeoutSeconds: 10])
  smartSelect(name: 'APPS', selectMode: 'multiple', filterable: true, description: '应用(多选)',
      source: [type: 'http', url: 'https://apps.example.com/api/v1/list', authType: 'bearer',
               credentialsId: 'apps-token', responseType: 'jsonArray'])
}
```

### 10.2 环境基线

| 项 | 值 |
|---|---|
| Jenkins 基线 | 2.528.x LTS（开发验证实例：本机 Docker，`http://localhost:8080`） |
| JDK | 17+（编译目标 17，容器用 21 构建） |
| 构建 | Maven + plugin parent POM 6.x + bom-2.528.x；`mvn verify`（SpotBugs 强制） |
