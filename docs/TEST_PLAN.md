# Smart Build Parameter Plugin 测试验证文档

- **版本**: v0.1（对应 [需求文档](./REQUIREMENTS.md) v0.1）
- **日期**: 2026-11-03
- **核心原则**: ① 新功能稳定可用；② **不得破坏 Jenkins 原有框架与功能**（回归红线，见 §7）。

---

## 1. 目的与范围

验证 REQUIREMENTS.md 中 FR-1 ~ FR-6 与 NFR-1 ~ NFR-5 的正确实现，以及插件与 Jenkins 核心及既有功能的兼容性。覆盖四层测试：**单元/集成（自动化）→ UI 结构（自动化）→ 本机真实 Jenkins 端到端（脚本化）→ 回归（自动化 + 手工清单）**。

## 2. 测试环境

| 环境 | 用途 | 说明 |
|---|---|---|
| EN-A 自动化测试 | 单元 / JenkinsRule 集成 / UI 结构断言 | Docker `maven:3.9-eclipse-temurin-21` 内 `mvn verify`（复用 `~/.m2` 缓存）；JenkinsRule 每类启动内存 Jenkins |
| EN-B 本机真实 Jenkins | 端到端 / 回归冒烟 / 截图 | 用户已部署：`http://localhost:8080`（WSL Docker，admin）。API Token 等敏感信息**不写入仓库文档**，从仓库未跟踪文件 `.local/jenkins.env` 读取（`JENKINS_URL` / `JENKINS_USER` / `JENKINS_TOKEN`） |

## 3. 测试策略与用例矩阵

用例编号规则：`TC-<需求号>-<序号>`；级别：`UT`(单元) `IT`(JenkinsRule 集成) `UI`(DOM/结构断言) `E2E`(真实 Jenkins) `REG`(回归) `SEC`(安全) `PERF`(性能)。

| 需求 | 自动化用例 | E2E/手工用例 |
|---|---|---|
| FR-1 | TC-FR1-001~007 | TC-FR1-E01~E02 |
| FR-2 | TC-FR2-001~005 | TC-FR2-E01 |
| FR-3 | TC-FR3-001~008 | TC-FR3-E01~E03 |
| FR-4 | TC-FR4-001~007 | TC-FR4-E01~E02 |
| FR-5 | TC-FR5-001~003 | TC-FR5-E01~E03 |
| FR-6 | TC-FR6-001~003 | TC-FR6-E01~E02 |
| 回归红线 | TC-REG-001~006 | TC-REG-E01~E03 |
| NFR | TC-SEC-001~004, TC-PERF-001~003 | — |

## 4. 自动化测试用例（EN-A，`mvn verify` 内执行）

### 4.1 FR-1 条件显隐（smartBoolean）

| 用例 | 级别 | 前置 | 步骤 | 预期 |
|---|---|---|---|---|
| TC-FR1-001 | IT | 定义 smartBoolean(controls:'X,Y') + string 参数 X、Y 的 Job | `configRoundtrip(job)` | 配置回读一致（name/defaultValue/controls/description） |
| TC-FR1-002 | UI | 同上，打开 build 页 | 断言参数块存在且带显隐数据属性（`data-controlled-by` 等） | 页面可渲染出显隐控制所需的稳定 DOM 钩子 |
| TC-FR1-003 | UT | 未勾选提交（模拟缺省触发，无该参数值） | 触发构建 | 构建 ParametersAction 中**无** X、Y（决策 D1） |
| TC-FR1-004 | UT | 勾选提交 X、Y 值 | 触发构建 | ParametersAction 含 X、Y 且取值正确 |
| TC-FR1-005 | UT | defaultValue=true | 渲染默认状态钩子 | 默认态为"显示" |
| TC-FR1-006 | IT | controls 含不存在名 'NOPE' | 保存配置 + 渲染 | 保存成功且配置页出现警告；表单忽略缺失项不报错 |
| TC-FR1-007 | IT | 声明式 Pipeline job 定义 `parameters { smartBoolean(...) }` | 首跑加载 Job 属性 | Job 参数定义注册成功，symbol `smartBoolean` 可用 |
| TC-FR1-008 | UI | smartBoolean 未勾选隐藏 minSelected=1 的 smartCheckbox，直接提交表单 | 隐藏参数豁免校验：表单可提交、构建正常，隐藏参数不在 ParametersAction（增补 B） |

### 4.2 FR-2 多选复选框（smartCheckbox）

| 用例 | 级别 | 步骤 | 预期 |
|---|---|---|---|
| TC-FR2-001 | IT | configRoundtrip | options/defaultChecked/delimiter/minSelected 回读一致 |
| TC-FR2-002 | UT | `createValue` 提交 [dev, test] | 值为按序拼接 `dev,test`（delimiter 默认 `,`） |
| TC-FR2-003 | UT | 空选择提交 | 值为空字符串，不抛错 |
| TC-FR2-004 | IT | 自定义 delimiter=`\|` | 拼接使用自定义分隔符 |
| TC-FR2-005 | UI | 渲染表单 | 复选框组渲染、defaultChecked 预勾选；minSelected>0 时提交按钮带校验属性 |
| TC-FR2-006 | IT | options 含分隔符字符（delimiter=","，选项 `a,b`）；或选项重复 | doCheckOptions 返回错误并说明冲突（增补 A） |
| TC-FR2-007 | IT | defaultChecked="nope"（不在 options 中） | doCheckDefaultChecked 返回警告/错误 |

### 4.3 FR-3 Shell 下拉（smartSelect, shell）

| 用例 | 级别 | 步骤 | 预期 |
|---|---|---|---|
| TC-FR3-001 | UT | doFill 请求，command=`printf 'a\n\nb\n'` | 选项恰为 [a, b]（空行过滤、去首尾空白） |
| TC-FR3-002 | UT | command=`exit 1` | 返回结构化错误（非 500），含退出码信息 |
| TC-FR3-003 | UT | command=`sleep 30`，timeout=2s | 2s 超时中止进程，返回超时错误 |
| TC-FR3-004 | UT | 输出 200KB，maxOutput=64KB | 截断并返回超限错误提示 |
| TC-FR3-005 | UT | cacheTtl=60，command=`echo x >> marker; printf ok` | 同 TTL 两次填充只执行一次（marker 行数=1）；TTL 过期后再执行（行数=2）；缓存线程安全（并发填充单次执行） |
| TC-FR3-006 | UT | selectMode=multiple，提交 [b, a] | 值为 `b,a`（保持勾选顺序） |
| TC-FR3-007 | UI | 渲染表单 | 下拉组件含过滤输入与列表容器；单/多选模式 DOM 语义正确（radio/checkbox） |
| TC-FR3-008 | IT | Pipeline `parameters { smartSelect(...) }` | 注册成功并可触发构建取值 |

### 4.4 FR-4 HTTP 下拉（smartSelect, http）

测试内嵌轻量 HTTP 桩（`com.sun.net.httpserver.HttpServer`）作为第三方系统。

| 用例 | 级别 | 步骤 | 预期 |
|---|---|---|---|
| TC-FR4-001 | UT | 桩返回 `["a","b","c"]`（jsonArray） | 选项 [a,b,c] |
| TC-FR4-002 | UT | 桩返回 `{"data":{"list":[{"name":"x"},{"name":"y"}]}}` + jsonPath=`data.list[*].name` | 选项 [x,y] |
| TC-FR4-003 | UT | 桩返回 `a\nb`（textLines） | 选项 [a,b] |
| TC-FR4-004 | SEC | basic 凭证（测试凭证库注入） | 桩断言收到 `Authorization: Basic base64(user:pass)` |
| TC-FR4-005 | SEC | bearer Secret text 凭证 | 桩断言收到 `Authorization: Bearer <secret>`；响应/页面/日志中不出现 secret 明文 |
| TC-FR4-006 | UT | 桩返回 401 / 超时（不响应）/ 非法 JSON | 三种场景均为结构化内联错误，非 500 |
| TC-FR4-007 | IT | Pipeline `parameters { smartSelect(source: http(url:'…')) }` | 注册成功并可构建取值 |

### 4.5 FR-5 / FR-6 呈现层（结构断言；视觉效果走 E2E）

| 用例 | 级别 | 步骤 | 预期 |
|---|---|---|---|
| TC-FR5-001 | UI | 打开 build 页 | 存在 scoped 样式/脚本资源注入标记（本插件命名空间），核心资源未被替换 |
| TC-FR5-002 | IT | 样式开关关闭（系统属性） | 注入行为关闭，DOM 与关闭前差异仅在插件注入部分 |
| TC-FR5-003 | UI | 模拟 JS 资源不可用 | 原生表单控件仍存在且可提交（form action 不依赖插件 JS） |
| TC-FR6-001 | IT | 构建后取 build ParametersAction 页面 | 新类型参数值正确出现（单测直接断言 ParameterValue 类型的呈现视图解析到插件视图） |
| TC-FR6-002 | UI | 多选值 | chips 展示结构存在，原始拼接串存在 |
| TC-FR6-003 | IT | `<build>/api/json` | parameters 数据结构与 Jenkins 惯例一致（不破坏 API 消费方） |
| TC-FR6-004 | UT | `createValue(req, jo)` 收到**字符串** `value`（API 直发 `json` / 浏览器单选提交） | 与 `createValue(String)` 同语义：按 delimiter 重拆成 choices（矩阵仿真发现的巨型 chip 回归） |

## 5. 本机真实 Jenkins 端到端验证（EN-B，脚本化）

> 凭据从 `.local/jenkins.env` 读取；REST 一律 `-u <user>:<apiToken>`(Basic——该实例以 API Token 作 Bearer 头会被当作匿名拒绝)。以下步骤同时是验收演示脚本。

**E-0 部署**：`mvn package` 产物 `target/*.hpi` → 通过 Plugin Manager API 上传安装 → 重启 → 断言插件 active 且无启动错误日志。

| 用例 | 场景 | 验证点 |
|---|---|---|
| TC-FR1-E01 | 种子 Job：声明式 Pipeline 使用 smartBoolean + 受控 string/checkbox | 浏览器勾选/取消，关联参数即时显隐；分别以两种状态构建，Parameters 页与 console 中 params 与预期一致（未勾选时无受控参数） |
| TC-FR1-E02 | Replay 上一次构建 | 重放构建参数记录与预期一致 |
| TC-FR2-E01 | smartCheckbox 多选 | 构建后 Parameters 页 chips 展示 + `params.ENV` 值正确 |
| TC-FR3-E01 | smartSelect(shell)：`git ls-remote` 或 `ls` 类命令 | 下拉出现命令输出；输入过滤生效 |
| TC-FR3-E02 | shell 失败命令 | 表单内联错误，其他参数仍可提交构建 |
| TC-FR3-E03 | cacheTtl=60 | 连续两次打开表单，命令仅执行一次（控制台/标记文件） |
| TC-FR4-E01 | smartSelect(http)：指向可公开访问的 JSON 接口 + 凭证库 bearer | 下拉取值正确；Jenkins 凭证库中创建的 token 被使用 |
| TC-FR4-E02 | http 401 | 内联错误呈现 |
| TC-FR5-E01 | 截图对比：安装前后 Build With Parameters | 现代化布局生效；原生参数排版正常 |
| TC-FR5-E02 | 关闭样式开关 | 页面回到原生样式 |
| TC-FR5-E03 | 混排页（原生+全部新类型） | 全部可填写提交，构建成功 |
| TC-FR6-E01 | 构建历史 Parameters 页 | 新类型取值正确展示（布尔徽章/多值 chips/超长截断） |
| TC-FR6-E02 | API 消费方 | `api/json` 参数数据符合惯例，与脚本消费兼容 |
| TC-SIM-E01 | 业务仿真：发布流水线（原生 string/boolean + smartBoolean 审批门 + smartCheckbox + smartSelect×2：shell / http+bearer）混排 | 全链路：构建页现代布局与审批联动隐藏（`field-disabled`+`display:none`）；全参数提交后 console/历史页/api/json 三方一致；审批关闭时受控参数不进提交动作（D1）；Replay 后历史页逐位一致；凭据明文不出现在任何页面/日志（TC-SEC-001 回归） |
| TC-SIM-E02 | 全场景参数矩阵：16 参数 = 原生 string/boolean/choice + 两态门×2（默认开/关）+ smartCheckbox×4（默认选中+minSelected、无默认可空、`\|` 元字符分隔符、248 字符超长选项）+ smartSelect(shell)×3（单选+过滤 / 多选+TTL 缓存 / 失败源）+ smartSelect(http)×4（jsonArray+bearer、jsonPath+basic、textLines 无鉴权、恒 401） | 提交场景×3：满配数组（顺序保留）/ 关门+空选（D1 + `(empty)` 标记 + 隐藏免除 minSelected）/ 预拼接字符串（按分隔符重拆）；页面断言 10/10 + 场景断言 39/39 |

### 5.1 执行记录（本机真实 Jenkins 2.580.2-lts）

| 日期 | 用例 | 结果 | 证据（实例上的 Job / 留档） |
|---|---|---|---|
| 2026-11-03 | TC-FR1-E01/E02、TC-FR2-E01、TC-FR3-E01/E02/E03 | PASS | Job `smart-fr1-e2e`、`smart-fr2-e2e`、`smart-fr3-e2e` / `-err` / `-cache`；构建日志与页面断言 |
| 2026-11-04 | TC-FR4-E01/E02 | PASS | Job `smart-fr4-e2e`、`smart-fr4-401`；`python:3-alpine` stub + 凭证库 bearer |
| 2026-11-04 | TC-FR5-E01/E02/E03 | PASS | Job `smart-fr5-mixed`；杀开关经 script console 切换实测（off→零注入、原生页仍可提交）；截图 `.local/screenshots/fr5-*.png`（本机留档，不入库） |
| 2026-11-04 | TC-FR6-E01/E02 | PASS | Job `smart-fr6-e2e`；截图 `fr6-params.png`；api/json 逐字段断言、无 `choices` 泄漏 |
| 2026-11-04 | TC-SIM-E01（含 D1、Replay、SEC-001 回归） | PASS | Job `smart-sim-release` builds #1–#4；stub 容器 `fr6-stub`（bearer 保护）；截图 `sim-build-page.png`；Post-JS DOM 断言 `sbpd-card` ×6、`field-disabled` ×2 |
| 2026-11-04 | TC-SIM-E02（全场景参数矩阵） | PASS | Job `smart-sim-full` builds #1–#4（stub 扩展四类端点 + 凭据 `fr4-basic-e2e`）；驱动脚本 `.local/sim-matrix.sh`（未入库）；Post-JS DOM：16 块全 `sbpd-card`、2 块默认 `field-disabled`+`display:none`、失败源内联错误；截图 `sim-full-params.png`。**发现并修复 Bug**（补单测 TC-FR6-004）：`createValue(req,jo)` 收到字符串值未按分隔符重拆 → 历史页巨型 chip；修复后 `mvn verify` 72/72，全矩阵重跑 10/10 + 39/39 |
| 2026-11-04 | **用户反馈 UI 修复轮**（TC-UI-FIX-01/02/03）：①隐藏范围扩到整条 `f:entry` 行（原只藏参数块 → 空标题行残留）；②多选 smartSelect 重设计为常规单行下拉（去确认按钮、勾选即时回显、外部点击/Esc 关闭、选项正常字号 + 新增 `css/smart-params.css` 复选框字号）；③`sbpd-actions` 改挂表单最后一个非 smartSelect 的 primary 按钮（原"第一个 primary"错挂下拉面板导致 flex 毁布局/巨型按钮）并保持文档流 | PASS | `mvn verify` 72/72（新增断言：条目行同步隐藏、无 confirm 按钮、即时回显拼接值、actions 挂点不在组件内）；真实浏览器 DOM：门关时 REGIONS/SERVICES 整行 `display:none`、门开时整行可见；截图 `ui-fix-closed/open/dropdown.png`；UI 修复后全矩阵重跑 10/10 + 39/39 |

自动化侧（EN-A）：`mvn verify` 全绿，72/72（含 FR-6 新增 `SmartParameterValueTest` 5 例），SpotBugs 无告警。

## 6. 安全与性能用例

| 用例 | 验证点 |
|---|---|
| TC-SEC-001 | 凭证不回显：config.jelly 回读、页面源码、构建日志中无明文凭据（grep 断言） |
| TC-SEC-002 | XSS：选项值 `<script>alert(1)</script>` 经 shell/http 注入，表单与历史页均转义显示 |
| TC-SEC-003 | 权限：无 Configure 权限用户无法修改数据源命令/URL（依赖 Jenkins 权限模型，冒烟验证） |
| TC-SEC-004 | SSRF 面文档化确认（NFR-2④，二期白名单） |
| TC-PERF-001 | 表单渲染（含 50 静态选项 + 缓存命中的动态源）P95 < 2s |
| TC-PERF-002 | 超时配置生效：shell 5s / http 10s 默认值被遵守 |
| TC-PERF-003 | 并发打开表单 ×10：无命令风暴（TTL 缓存单飞）、无线程错误 |

## 7. 回归红线验证（"不破坏"）

自动化（EN-A）：

| 用例 | 验证点 |
|---|---|
| TC-REG-001 | 同一 Job 混排原生 string/choice/boolean + 新类型：全部渲染、提交、取值正确 |
| TC-REG-002 | 仅原生参数的 Job：安装插件前后 `config.xml` 无 diff、构建行为一致 |
| TC-REG-003 | 禁用插件后：内存 Jenkins 页面正常、原生参数 Job 正常（`jenkins-test-harness` 禁用冒烟） |
| TC-REG-004 | 声明式 Pipeline（不用新参数）语法解析与执行不受影响 |
| TC-REG-005 | 插件序列化兼容：旧版配置 XML 在新版本可读（字段演进只增不删，`readResolve` 兼容） |
| TC-REG-006 | SpotBugs / 基线一致性随 `mvn verify` 强制通过 |

真实环境（EN-B）：

| 用例 | 验证点 |
|---|---|
| TC-REG-E01 | 部署插件前后，既有 Job（原生参数）Build With Parameters 截图/DOM 对比一致 |
| TC-REG-E02 | 卸载/禁用插件后 Jenkins 启动无错误，既有 Job 可构建 |
| TC-REG-E03 | Jenkins 系统日志无本插件相关 SEVERE/WARN 异常 |

## 8. 出口准则（Definition of Done，一期）

1. §4 全部自动化用例通过（`mvn verify` 绿，含 SpotBugs）；
2. §5 E2E 用例在本机 Jenkins 全部通过，附截图/记录；
3. §7 回归红线全部通过；
4. 插件在真实 Jenkins 上安装、启用、禁用、卸载全程无系统级错误；
5. 文档（README + REQUIREMENTS + TEST_PLAN）与实现一致。

## 9. 风险与测试关注点

| 风险 | 测试对策 |
|---|---|
| Shell 源在控制器执行的安全面 | SEC/权限用例 + 文档明示；二期沙箱 |
| 前端组件与核心/其他插件 JS 冲突 | 回归 E01/E03 + FR5-003 降级用例 |
| 弱网下动态源拖慢表单 | PERF 用例 + 默认超时与内联错误呈现 |
| 多选值拼接语义与用户预期偏差 | 需求决策 D2 已固化；文档与 UI 描述保持一致 |
