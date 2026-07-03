# AIBILL Android 测试方案

> 版本: v1.0  
> 日期: 2026-07-02  
> 作者: likunhong  
> 状态: 初稿

---

## 一、测试策略总览

### 1.1 测试金字塔

```
            ┌──────────┐
            │  E2E 测试  │  ← 少量关键流程（手动 + 自动化）
            │  (10%)    │
           ┌┴──────────┴┐
           │  集成测试    │  ← Repository + API + DB 联合
           │  (20%)      │
          ┌┴────────────┴┐
          │   单元测试     │  ← ViewModel / UseCase / Parser / Utils
          │   (70%)       │
          └──────────────┘
```

### 1.2 测试工具矩阵

| 测试类型 | 覆盖范围 | 工具 | 运行环境 |
|----------|----------|------|----------|
| 单元测试 | ViewModel、UseCase、工具类、解析器 | JUnit5 + MockK + Turbine | JVM (本地) |
| 单元测试(Android依赖) | Context/Notification/Intent 等 | JUnit5 + Robolectric | JVM (本地) |
| 集成测试 | Repository + Room + API | JUnit5 + MockWebServer + Room inMemory | JVM / Instrumented |
| UI 测试 | Compose 组件 + 页面交互 | Compose Testing + Espresso | Instrumented (设备/模拟器) |
| 截图测试 | UI 视觉回归（深色模式等） | Paparazzi / Roborazzi | JVM (本地) |
| E2E 测试 | 核心用户流程 | Compose Testing + Hilt Testing | Instrumented |
| 性能测试 | 启动时间、帧率、内存 | Macrobenchmark + Perfetto | 真机 |
| 安全测试 | Token 存储、数据隔离 | 手动 + 自动化脚本 | 真机 |
| 覆盖率统计 | 全模块 | Kover (JetBrains) | JVM |

### 1.3 覆盖率目标

| 模块 | 目标覆盖率 | 说明 |
|------|-----------|------|
| domain/usecase/ | ≥ 90% | 核心业务逻辑必须高覆盖 |
| domain/model/ | ≥ 95% | 重点覆盖模型内的验证/计算方法 |
| data/repository/ | ≥ 85% | 数据层逻辑，同步相关必须 ≥ 90% |
| data/parser/ | ≥ 95% | 通知解析是自动记账核心入口 |
| presentation/viewmodel/ | ≥ 80% | 状态管理逻辑 |
| util/ | ≥ 95% | 工具函数必须全覆盖 |
| presentation/ui/ | ≥ 60% | UI 测试侧重关键交互 |
| 整体 | ≥ 75% | 行覆盖率 |

### 1.4 测试运行策略

| 时机 | 运行范围 | 工具 |
|------|----------|------|
| 提交前 (pre-commit) | 单元测试 | `./gradlew testDebugUnitTest` |
| PR 合入 | 单元 + 集成测试 | CI Pipeline |
| 每日构建 | 全量测试 + UI 测试 | CI + Firebase Test Lab |
| 发版前 | 全量 + E2E + 性能 | 手动 + CI |

---

## 二、单元测试用例

### 2.1 AI 解析模块 (ParseInputUseCase)

#### 2.1.1 本地规则匹配

| 用例 ID | 用例 | 输入 | 预期输出 |
|---------|------|------|----------|
| LR-001 | 历史匹配 - 完全命中 | "瑞幸" (本地规则: 瑞幸→咖啡) | 直接返回分类"咖啡"，不调 AI |
| LR-002 | 历史匹配 - 包含命中 | "瑞幸咖啡28" (规则: 瑞幸→咖啡) | 匹配成功，金额2800，分类"咖啡" |
| LR-003 | 无规则 - 降级AI | "新店消费50" (无本地规则) | 调用 AI API 解析 |
| LR-004 | 规则冲突 - 最近优先 | "星巴克" (规则1:咖啡, 规则2:餐饮) | 使用最后更新的规则 |
| LR-005 | 快捷短语匹配 | "午餐" (短语映射: 午餐→餐饮¥20) | 直接返回预设值 |

#### 2.1.2 AI 解析结果处理

| 用例 ID | 用例 | AI 返回 | 预期处理 |
|---------|------|---------|----------|
| AI-001 | 单条支出 | "午饭32" | `[{type:"expense", amount:3200, category:"餐饮"}]` |
| AI-002 | 多条批量 | "早餐8，午饭25，地铁4" | 3 条记录，金额 800/2500/400 |
| AI-003 | 收入 | "发工资12000" | `[{type:"income", amount:1200000, category:"工资"}]` |
| AI-004 | 转账 | "微信转支付宝500" | `[{type:"transfer", amount:50000}]` |
| AI-005 | 金额小数 | "打车15.5" | amount = 1550 |
| AI-006 | 无金额 | "买了点东西" | 返回解析失败，fallback 手动 |
| AI-007 | 日期推断 | "昨天午饭30" | date = 昨天日期 (YYYY-MM-DD) |
| AI-008 | 账户推断 | "微信付了咖啡28" | account_name = "微信" |
| AI-009 | 中文金额 | "花了三十二块" | amount = 3200 |
| AI-010 | 空输入 | "" | 返回参数错误 |
| AI-011 | AI 超时 | 模拟 >5s 响应 | 返回超时错误 + fallback |
| AI-012 | AI 返回非法 JSON | 乱码/非JSON | 降级为手动记账 |
| AI-013 | 快速连续输入 | 100ms 内输入两次 | 仅处理最后一次请求(debounce) |
| AI-014 | 解析中取消 | 输入后立即返回/旋转屏幕 | 取消协程，不更新 UI |
| AI-015 | 部分合法 JSON | [有效item, null, 有效item] | 过滤无效，返回有效 2 条 |
| AI-016 | 极长输入文本 | 超过 1000 字符 | 截断或返回错误提示 |

### 2.2 通知解析器 (NotificationParser)

| 用例 ID | 用例 | 包名 | 通知文本 | 预期 |
|---------|------|------|----------|------|
| NP-001 | 微信支付 - 标准 | com.tencent.mm | "微信支付成功，付款￥32.00" | amount=3200, type=expense |
| NP-002 | 微信支付 - 商家 | com.tencent.mm | "向沙县小吃付款￥25.00" | amount=2500, desc="沙县小吃" |
| NP-003 | 支付宝 - 标准 | com.eg.android.AlipayGphone | "支付宝付款￥15.00" | amount=1500, type=expense |
| NP-004 | 支付宝 - 收入 | com.eg.android.AlipayGphone | "收到转账￥200.00" | amount=20000, type=income |
| NP-005 | 银行短信 | SMS | "尾号1234消费￥500.00" | amount=50000, type=expense |
| NP-006 | 银行短信 - 收入 | SMS | "尾号1234收入￥12000.00" | amount=1200000, type=income |
| NP-007 | 无关通知 | com.tencent.mm | "你有一条新消息" | 返回 null (不解析) |
| NP-008 | 促销通知 | com.eg.android.AlipayGphone | "双11红包已到账" | 返回 null (忽略) |
| NP-009 | 金额为0 | com.tencent.mm | "付款￥0.00" | 返回 null (忽略零金额) |
| NP-010 | 超大金额 | com.tencent.mm | "付款￥999999.99" | amount=99999999, 标记需确认 |
| NP-011 | 非白名单应用 | com.other.app | 任意文本 | 返回 null (跳过) |
| NP-012 | 重复通知去重 | com.tencent.mm | 1s 内相同金额+来源 | 只处理第一条 |
| NP-013 | 通知权限撤回 | - | NotificationListenerService 断开 | 优雅降级，提示重新授权 |
| NP-014 | Extra 为 null | com.tencent.mm | notification.extras = null | 不崩溃，返回 null |
| NP-015 | 非人民币符号 | com.tencent.mm | "付款$50.00" | 返回 null 或标记需人工确认 |

### 2.3 置信度计算 (ConfidenceCalculator)

| 用例 ID | 场景 | 输入因素 | 预期置信度 | 预期行为 |
|---------|------|----------|-----------|----------|
| CF-001 | 全部命中 | 历史商家+明确金额+历史分类+信任来源+正则匹配 | ≥ 90% | 静默入库 |
| CF-002 | 部分命中 | 明确金额+信任来源+无历史 | 60-89% | 弹窗确认 |
| CF-003 | 低置信 | 模糊文本+新来源 | < 60% | 存入待审 |
| CF-004 | 商家历史加分 | 商家出现过3次 | +30% | - |
| CF-005 | 金额明确加分 | 正则提取到具体数字 | +30% | - |
| CF-006 | 分类一致加分 | 与历史同商家分类一致 | +20% | - |
| CF-007 | 信任来源加分 | 微信支付通知 | +10% | - |

### 2.4 金额工具类 (AmountUtils)

| 用例 ID | 方法 | 输入 | 预期输出 | 说明 |
|---------|------|------|----------|------|
| AM-001 | yuanToFen | 32.0 | 3200 | 整数元 |
| AM-002 | yuanToFen | 15.5 | 1550 | 一位小数 |
| AM-003 | yuanToFen | 99.99 | 9999 | 两位小数 |
| AM-004 | yuanToFen | 0.01 | 1 | 最小单位 |
| AM-005 | yuanToFen | 0.1 + 0.2 | 30 | Math.round 防浮点 |
| AM-006 | yuanToFen | 100000.0 | 10000000 | 大金额 |
| AM-007 | fenToYuan | 3200 | "32.00" | 正常转换 |
| AM-008 | fenToYuan | 1550 | "15.50" | 保留两位小数 |
| AM-009 | fenToYuan | 1 | "0.01" | 最小单位 |
| AM-010 | fenToYuan | 0 | "0.00" | 零值 |
| AM-011 | fenToYuan | -3200 | "-32.00" | 负值 (退款) |
| AM-012 | formatDisplay | 3200 | "¥32.00" | 带符号展示 |
| AM-013 | formatDisplay | -3200 | "-¥32.00" | 负值展示 |
| AM-014 | parseExpression | "32+15" | 4700 | 加法 |
| AM-015 | parseExpression | "100-30" | 7000 | 减法 |
| AM-016 | parseExpression | "15.5*2" | 3100 | 乘法 |
| AM-017 | parseExpression | "abc" | null (解析失败) | 非法输入 |
| AM-018 | parseExpression | "10/0" | null | 除零保护 |
| AM-019 | parseExpression | "" | null | 空字符串 |
| AM-020 | yuanToFen | Long.MAX_VALUE | 溢出保护/异常 | 极大值 |
| AM-021 | parseExpression | "10/3" | 333 (取整) | 除不尽处理 |

### 2.5 ViewModel 测试 (HomeViewModel)

| 用例 ID | 用例 | 操作 | 预期状态变化 |
|---------|------|------|-------------|
| VM-001 | 加载首页数据 | init | isLoading=true → 数据加载 → isLoading=false |
| VM-002 | AI 解析成功 | parseInput("午饭32") | aiParseResults = [1条结果] |
| VM-003 | AI 解析失败 | parseInput("???") | error = "AI 暂时无法理解" |
| VM-004 | 确认单条 | confirmItem(item) | aiParseResults 移除该条 |
| VM-005 | 全部确认 | confirmAll() | aiParseResults = null |
| VM-006 | 网络错误 | 无网络时加载 | error = "网络连接失败" |
| VM-007 | Token 过期 | 401 响应 | 触发导航到登录页 |

### 2.6 同步逻辑 (SyncPendingUseCase)

| 用例 ID | 用例 | 前置条件 | 预期行为 |
|---------|------|----------|----------|
| SY-001 | 无待同步数据 | pendingList 为空 | 直接返回 Success |
| SY-002 | 同步成功 | 3条 pending | 全部标记 synced |
| SY-003 | 部分失败 | 5条中2条失败 | 3条 synced，2条 failed+retryCount++ |
| SY-004 | 幂等重试 | 重复提交同一 client_id | 服务端返回 duplicates，本地标记 synced |
| SY-005 | 网络中断 | 同步过程中断网 | retry，WorkManager 重新调度 |
| SY-006 | 超出重试次数 | retryCount >= 5 | 标记 failed，不再自动重试 |
| SY-007 | 批量分割 | 120条待同步 | 分3批(50+50+20)依次同步 |
| SY-008 | 同步中修改记录 | 用户编辑正在同步的 pending | 同步完成后以最新编辑为准 |
| SY-009 | Token 同步中过期 | 批次进行中返回 401 | 中断同步，触发重新登录 |
| SY-010 | 进程被杀恢复 | 同步 30 条后 App 被 kill | WorkManager 重新调度，已同步的不重复(幂等) |


---

## 三、集成测试用例

### 3.1 认证流程 (AuthRepository + API)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| IT-AUTH-001 | 正确密码登录 | POST /auth/login {正确凭据} | 返回 token + user 信息 |
| IT-AUTH-002 | 错误密码登录 | POST /auth/login {错误密码} | 返回 401 错误 |
| IT-AUTH-003 | 有效邀请码注册 | POST /auth/register {有效邀请码} | 注册成功 + 默认分类生成 |
| IT-AUTH-004 | 过期邀请码注册 | POST /auth/register {过期码} | 返回 400 |
| IT-AUTH-005 | 重复用户名注册 | POST /auth/register {已存在用户名} | 返回 400 |
| IT-AUTH-006 | Token 有效性校验 | GET /auth/me {有效token} | 返回用户信息 |
| IT-AUTH-007 | Token 过期 | GET /auth/me {过期token} | 返回 401，触发清除Token |
| IT-AUTH-008 | 伪造 Token | GET /auth/me {篡改token} | 返回 401 |
| IT-AUTH-009 | 无 Token 请求 | GET /transactions (无Authorization) | 返回 401 |

### 3.2 交易 CRUD (TransactionRepository + Room + API)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| IT-TXN-001 | 在线创建单条 | 有网络 + 创建支出 | API 返回成功 + 本地缓存写入 |
| IT-TXN-002 | 在线批量创建 | 有网络 + 3条交易 | API created.length=3 |
| IT-TXN-003 | 离线创建 | 无网络 + 创建支出 | Room 写入 pending 记录 |
| IT-TXN-004 | 离线后同步 | pending 记录 + 网络恢复 | SyncWorker 成功同步 |
| IT-TXN-005 | 幂等验证 | 相同 client_id 提交两次 | 第二次返回 duplicates |
| IT-TXN-006 | 分页查询 | page=1, pageSize=10, total=25 | 返回 10 条，hasMore=true |
| IT-TXN-007 | 日期筛选 | startDate=2026-07-01 | 只返回该日期之后数据 |
| IT-TXN-008 | 关键词搜索 | keyword="午饭" | 只返回描述含"午饭"的 |
| IT-TXN-009 | 修改交易 | PUT 更新金额 | 金额更新 + updatedAt 更新 |
| IT-TXN-010 | 软删除 | DELETE 交易 | deleted_at 有值，列表不显示 |
| IT-TXN-011 | 删除后不计入统计 | 删除后查 stats | 金额不含已删除 |
| IT-TXN-012 | 转账不计入统计 | 创建 transfer | 不影响收支统计 |
| IT-TXN-013 | 跨用户隔离 | user1 创建，user2 查询 | user2 查不到 |

### 3.3 Room 数据库 (本地存储)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| IT-DB-001 | Pending 写入 | insert PendingTransaction | 记录存在，syncStatus=pending |
| IT-DB-002 | Pending 查询 | getAllPending() | 只返回 status=pending 的记录 |
| IT-DB-003 | 状态更新 | updateSyncStatus(id, "synced") | 状态变更成功 |
| IT-DB-004 | 重试计数 | incrementRetryCount(id) | retryCount +1, lastError 更新 |
| IT-DB-005 | 分类缓存 | 插入后查询 | 数据一致 |
| IT-DB-006 | 账户缓存 | 插入后查询 | 数据一致 |
| IT-DB-007 | 通知记录 | 插入 + 状态更新 | raw → parsed → confirmed |
| IT-DB-008 | 数据库升级 | Migration 1→2 | 数据不丢失，新字段有默认值 |
| IT-DB-009 | Flow 响应 | 插入后 Flow 发射 | 观察者收到新数据 |

### 3.4 统计接口 (StatsRepository)

| 用例 ID | 用例 | 前置数据 | 验证 |
|---------|------|----------|------|
| IT-STAT-001 | 月度摘要正确 | 3笔支出(3200+1500+2800) + 1笔收入(1200000) | expense=7500, income=1200000, balance=1192500 |
| IT-STAT-002 | 转账不计入 | 1笔转账50000 | 不影响 expense/income |
| IT-STAT-003 | pending 不计入 | 1笔 pending 交易 | 不出现在统计 |
| IT-STAT-004 | 已删除不计入 | 删除1笔后查统计 | 金额不含已删除 |
| IT-STAT-005 | 分类排行 | 餐饮5000+交通2000+购物3000 | 按金额降序: 餐饮>购物>交通 |
| IT-STAT-006 | 日趋势 | 3天各有支出 | 每天金额正确 |
| IT-STAT-007 | 环比计算 | 本月8000 上月6000 | 环比 +33.3% |

### 3.5 预算模块 (BudgetRepository)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| IT-BDG-001 | 创建总预算 | POST budget(category_id=0) | 创建成功 |
| IT-BDG-002 | 创建分类预算 | POST budget(category_id=1) | 创建成功 |
| IT-BDG-003 | 使用率计算 | 预算10000分 + 支出8500分 | percent=85% |
| IT-BDG-004 | 唯一约束 | 同月同分类重复创建 | 返回冲突错误 |
| IT-BDG-005 | 超支状态 | 支出 > 预算 | status=exceeded |
| IT-BDG-006 | 修改预算 | PUT 更新额度 | 新额度生效 |
| IT-BDG-007 | 删除预算 | DELETE | 预算移除 |

---

## 四、UI 测试用例

### 4.1 登录页面 (LoginScreen)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| UI-LOGIN-001 | 输入合法凭据登录 | 输入用户名密码 → 点击登录 | 跳转到首页 |
| UI-LOGIN-002 | 空用户名 | 不填用户名 → 点击登录 | 显示错误提示 |
| UI-LOGIN-003 | 空密码 | 不填密码 → 点击登录 | 显示错误提示 |
| UI-LOGIN-004 | 登录中加载态 | 点击登录 → API 请求中 | 按钮显示 loading |
| UI-LOGIN-005 | 登录失败提示 | 错误密码 → 点击登录 | Snackbar 显示错误信息 |
| UI-LOGIN-006 | 服务器地址可配置 | 点击服务器设置 | 弹出地址配置页面 |

### 4.2 首页 (HomeScreen)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| UI-HOME-001 | 月度支出展示 | 页面加载 | 顶部显示正确的月支出金额 |
| UI-HOME-002 | AI 输入框存在 | 页面加载 | 输入框可见且可聚焦 |
| UI-HOME-003 | 输入后触发解析 | 输入"午饭32" → 提交 | 显示确认卡片 |
| UI-HOME-004 | 快捷短语点击 | 点击"午餐"标签 | 文本填入输入框 |
| UI-HOME-005 | 确认卡片展示 | AI 解析成功 | 卡片显示金额/分类/描述 |
| UI-HOME-006 | 单条确认 | 点击确认按钮 | 卡片消失 + Toast成功 |
| UI-HOME-007 | 全部确认 | 多条结果 → 点击全部确认 | 所有卡片消失 |
| UI-HOME-008 | 修改解析结果 | 点击修改 | 跳转编辑页面 |
| UI-HOME-009 | 今日流水展示 | 有今日交易 | 列表正确展示 |
| UI-HOME-010 | 下拉刷新 | 下拉页面 | 数据刷新 |
| UI-HOME-011 | 通知红点 | 有待确认通知 | 铃铛图标显示红点 |

### 4.3 手动记账页 (RecordScreen)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| UI-REC-001 | 类型切换 | 点击 支出/收入/转账 Tab | 分类列表对应变化 |
| UI-REC-002 | 金额输入 | 点击数字键盘 | 金额正确显示 |
| UI-REC-003 | 计算器功能 | 输入"32+15" | 显示计算结果 47 |
| UI-REC-004 | 分类选择 | 点击分类图标 | 图标高亮选中 |
| UI-REC-005 | 必填校验 - 金额为0 | 不输入金额 → 保存 | 提示"请输入金额" |
| UI-REC-006 | 必填校验 - 无分类 | 不选分类 → 保存 | 提示"请选择分类" |
| UI-REC-007 | 保存成功 | 填写完整 → 保存 | Toast "记账成功" |
| UI-REC-008 | 连续记账 | 保存后 | 表单清空，停留当前页 |
| UI-REC-009 | 日期选择 | 点击日期 | 弹出日期选择器 |
| UI-REC-010 | 转账目标账户 | 选择转账类型 | 显示目标账户字段 |

### 4.4 流水页面 (TransactionsScreen)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| UI-TXN-001 | 按日分组 | 页面加载 | 日期分组标题 + 日小计 |
| UI-TXN-002 | 搜索功能 | 输入关键词 | 列表实时过滤 |
| UI-TXN-003 | 筛选面板 | 点击筛选按钮 | BottomSheet 弹出 |
| UI-TXN-004 | 上拉加载 | 滑动到底部 | 加载下一页 |
| UI-TXN-005 | 下拉刷新 | 下拉 | 数据刷新 |
| UI-TXN-006 | 左滑删除 | 左滑流水项 | 显示删除按钮 |
| UI-TXN-007 | 删除确认 | 点击删除按钮 | Dialog 二次确认 |
| UI-TXN-008 | 点击进详情 | 点击流水项 | 跳转详情编辑页 |
| UI-TXN-009 | 空状态 | 无数据 | 显示空状态引导 |
| UI-TXN-010 | 收支颜色 | 列表展示 | 支出红色，收入绿色 |

### 4.5 统计页面 (StatisticsScreen)

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| UI-STAT-001 | 月份切换 | 点击左/右箭头 | 数据刷新为对应月份 |
| UI-STAT-002 | Tab 切换 | 支出 ↔ 收入 | 图表数据切换 |
| UI-STAT-003 | 趋势图渲染 | 页面加载 | 折线图正确展示 |
| UI-STAT-004 | 饼图渲染 | 页面加载 | 饼图正确展示 |
| UI-STAT-005 | 排行列表 | 页面加载 | 按金额降序排列 |
| UI-STAT-006 | 数据点交互 | 点击图表数据点 | 显示具体数值 |

### 4.6 深色模式

| 用例 ID | 用例 | 操作 | 验证 |
|---------|------|------|------|
| UI-DM-001 | 浅色模式 | 设置为 Light | 全局浅色主题 |
| UI-DM-002 | 深色模式 | 设置为 Dark | 全局深色主题 |
| UI-DM-003 | 跟随系统 | 设置为 System | 随系统切换 |
| UI-DM-004 | 图表适配 | 深色模式下 | 图表颜色适配深色背景 |
| UI-DM-005 | 切换不丢失状态 | 模式切换 | 页面数据保持 |


---

## 五、E2E 测试用例（核心用户流程）

### 5.1 首次使用完整流程

| 用例 ID | 场景 | 步骤 | 验证点 |
|---------|------|------|--------|
| E2E-001 | 首次配置到登录 | 启动 App → 输入服务器地址 → 连通性测试 → 登录 | 成功进入首页 |
| E2E-002 | 注册流程 | 点击注册 → 输入用户名/密码/邀请码 → 提交 | 注册成功，进入首页 |
| E2E-003 | AI 记账完整流程 | 首页输入"午饭32" → AI 解析 → 确认卡片 → 确认 | 流水列表出现该记录 |
| E2E-004 | AI 批量记账 | 输入"早餐8，午饭25，地铁4" → 3张卡片 → 全部确认 | 3笔入库，首页今日流水更新 |
| E2E-005 | 手动记账完整流程 | FAB → 手动记账 → 填写支出/32/餐饮 → 保存 | 流水列表出现该记录 |
| E2E-006 | 编辑交易 | 流水页点击记录 → 修改金额为50 → 保存 | 金额更新为 ¥50.00 |
| E2E-007 | 删除交易 | 流水页左滑 → 删除 → 确认 | 记录消失，统计更新 |
| E2E-008 | AI 失败降级 | mock AI 超时 → 弹出提示 → 切换手动记账 | 手动记账页预填原始输入 |

### 5.2 离线场景

| 用例 ID | 场景 | 步骤 | 验证点 |
|---------|------|------|--------|
| E2E-009 | 离线手动记账 | 断网 → 手动记账 → 保存 | 本地保存成功，标记 pending |
| E2E-010 | 离线后同步 | 恢复网络 | 自动同步，pending → synced |
| E2E-011 | 离线查看流水 | 断网 → 查看流水列表 | 展示本地缓存的最近7天数据 |
| E2E-012 | 同步失败重试 | 同步失败 → 再次恢复网络 | WorkManager 自动重试 |

### 5.3 通知监听场景

| 用例 ID | 场景 | 步骤 | 验证点 |
|---------|------|------|--------|
| E2E-013 | 通知自动识别 | 收到微信支付通知 | 通知中心出现待确认项 |
| E2E-014 | 通知弹窗确认 | App 后台 + 收到通知 | Heads-up 通知展示 + 确认入库 |
| E2E-015 | 通知忽略 | 点击忽略按钮 | 标记 ignored，不再提醒 |
| E2E-016 | 批量确认 | 通知中心 → 全部确认 | 所有 pending 标记 confirmed |
| E2E-017 | 免打扰时段 | 设置免打扰 22:00-8:00 → 凌晨收到通知 | 不弹窗，静默存入待确认 |

### 5.4 预算场景

| 用例 ID | 场景 | 步骤 | 验证点 |
|---------|------|------|--------|
| E2E-018 | 预算设置 | 设置月总预算 5000 元 | 预算页显示进度条 |
| E2E-019 | 预算追踪 | 记账后查看预算 | 已用金额更新，进度条变化 |
| E2E-020 | 超支提醒 | 支出超过预算 | 收到本地推送通知 |

### 5.5 稳定性与恢复场景

| 用例 ID | 场景 | 步骤 | 验证点 |
|---------|------|------|--------|
| E2E-021 | 版本升级数据迁移 | 旧版本 100 条数据 → 安装新版本 | 数据完整，DB 迁移成功 |
| E2E-022 | 大数据量性能 | 账号内有 5000+ 条交易 | 列表性能可接受，统计正确 |
| E2E-023 | 服务器不可达恢复 | 服务器宕机→离线使用→恢复→同步 | 数据 0 丢失 |
| E2E-024 | App Crash 后数据完整 | 记账中 App 崩溃→重启 | pending 数据仍在，可同步 |
| E2E-025 | 长时间后台运行 | App 运行 24h 后 | 通知监听仍工作，无内存泄漏 |

---

## 六、性能测试

### 6.1 启动性能

| 指标 | 目标 | 测试方法 | 工具 |
|------|------|----------|------|
| 冷启动时间 | < 2s | Application 创建到首屏渲染完成 | Macrobenchmark |
| 温启动时间 | < 1s | 从后台恢复到首屏可交互 | Macrobenchmark |
| 首屏数据加载 | < 1.5s | 首页月度数据 + 今日流水加载完成 | Trace 打点 |

### 6.2 运行时性能

| 指标 | 目标 | 测试方法 | 工具 |
|------|------|----------|------|
| 列表滚动帧率 | ≥ 60fps | 流水列表快速滚动 (500条+) | Perfetto + GPU Profiler |
| 页面切换 | < 300ms | Tab 切换/页面跳转动画完成 | Trace |
| AI 解析端到端 | < 5s | 输入提交到确认卡片展示 | 打点计时 |
| 搜索响应 | < 500ms | 输入关键词到列表更新 | 打点计时 |
| 图表渲染 | < 1s | 统计页图表完整渲染 | Trace |
| ANR 率 | 0 | 所有操作响应 < 5s（主线程不阻塞） | StrictMode + Perfetto |
| 主线程阻塞 | < 16ms/帧 | 无数据库/网络操作在主线程 | StrictMode |
| 流水查询 (1万条) | < 200ms | 按月分页查询 | Room + Trace |
| 统计聚合 (1万条) | < 500ms | 月度/分类聚合 | Room + Trace |

### 6.3 资源占用

| 指标 | 目标 | 测试方法 | 工具 |
|------|------|----------|------|
| 内存峰值 | < 150MB | 正常使用场景（浏览/记账/统计） | Android Profiler |
| 内存泄漏 | 0 | 反复进出页面，观察内存增长 | LeakCanary |
| APK 体积 | < 30MB | Release 包大小 | `./gradlew assembleRelease` |
| 数据库大小 | < 10MB/千条 | 1000条交易后数据库体积 | 文件系统 |

### 6.4 网络性能

| 指标 | 目标 | 测试方法 | 工具 |
|------|------|----------|------|
| API 请求超时 | 10s 上限 | 慢网络模拟 | Charles / OkHttp 配置 |
| 同步吞吐 | 50条/批 < 5s | 批量同步 50 条 pending | 打点计时 |
| 弱网体验 | 可降级使用 | 3G 限速 (300kbps) | 模拟器网络设置 |

### 6.5 电池消耗

| 指标 | 目标 | 测试方法 |
|------|------|----------|
| 后台耗电 | < 2%/小时 | 通知监听服务运行中的电池消耗 |
| 同步耗电 | 每次同步 < 0.1% | WorkManager 执行同步任务 |
| 整体日耗 | < 5% | 正常使用一天的总消耗 |

---

## 七、安全测试

### 7.1 认证安全

| 用例 ID | 用例 | 测试方法 | 预期 |
|---------|------|----------|------|
| SEC-001 | Token 加密存储 | 查看 SharedPreferences 文件 | Token 不可明文读取 |
| SEC-002 | Token 过期处理 | 手动修改 Token 过期时间 | 自动跳转登录 |
| SEC-003 | Token 伪造 | 修改 JWT payload | 服务端返回 401 |
| SEC-004 | 密码不缓存 | 登录后检查本地存储 | 无密码明文存储 |
| SEC-005 | 登出清除 | 退出登录 | Token + 用户数据清除 |

### 7.2 数据安全

| 用例 ID | 用例 | 测试方法 | 预期 |
|---------|------|----------|------|
| SEC-006 | 日志无敏感信息 | 检查 Logcat 输出 | 无 Token/密码/金额明文 |
| SEC-007 | 数据隔离 | 修改 user_id 请求他人数据 | 返回 404 (非 403) |
| SEC-008 | SQL 注入 | 搜索输入 `'; DROP TABLE--` | 无影响，正常返回 |
| SEC-009 | 代码混淆 | 反编译 Release APK | 类名/方法名已混淆 |
| SEC-010 | HTTPS 支持 | 配置 HTTPS 服务器 | 正常通信 |
| SEC-011 | 自签证书支持 | 配置自签 HTTPS | NetworkSecurityConfig 允许 |

### 7.3 通知安全

| 用例 ID | 用例 | 测试方法 | 预期 |
|---------|------|----------|------|
| SEC-012 | 通知隐私模式 | 开启隐私模式 | 通知不显示具体金额 |
| SEC-013 | 通知数据不泄露 | 检查通知内容 | 不包含 Token/完整账号 |
| SEC-014 | 应用锁有效性 | 开启后切到后台再回来 | 需要生物认证 |

### 7.4 组件安全

| 用例 ID | 用例 | 测试方法 | 预期 |
|---------|------|----------|------|
| SEC-015 | exported Activity 越权 | adb 启动未暴露 Activity | 无法绕过认证直接访问 |
| SEC-016 | Intent 注入 | 构造恶意 Intent 数据 | App 不崩溃，不执行异常操作 |
| SEC-017 | Deeplink 伪造 | 构造恶意 deeplink URL | 不绕过登录态 |
| SEC-018 | 调试模式泄露 | 检查 Release 包 | android:debuggable=false |
| SEC-019 | 数据库文件访问 | Root 设备读取 db 文件 | 考虑 SQLCipher 加密 |
| SEC-020 | 数据库备份防护 | adb backup | allowBackup=false 或加密 |

### 7.5 网络安全

| 用例 ID | 用例 | 测试方法 | 预期 |
|---------|------|----------|------|
| SEC-021 | 中间人攻击 (MITM) | Charles/Fiddler 代理抓包 | 自签证书场景可控，公网需 HTTPS |
| SEC-022 | 超长输入攻击 | 10000 字符的描述/输入 | 不导致 OOM/ANR |
| SEC-023 | 特殊字符 | emoji/零宽字符/RTL 字符 | 正常显示，不影响布局 |

---

## 八、兼容性测试

### 8.1 设备覆盖

| 维度 | 覆盖范围 |
|------|----------|
| Android 版本 | 8.0 (API 26) / 10 (API 29) / 12 (API 31) / 13 (API 33) / 14 (API 34) |
| 屏幕尺寸 | 5.0" (小屏) / 6.1" (标准) / 6.7" (大屏) |
| 分辨率 | 720p / 1080p / 1440p |
| ROM | 原生 AOSP / MIUI / EMUI / ColorOS / OneUI |
| 内存 | 4GB (低端) / 8GB (中端) / 12GB+ (高端) |

### 8.2 ROM 兼容重点

| 品牌 | 重点测试项 | 说明 |
|------|-----------|------|
| 小米 MIUI/HyperOS | 通知监听存活 + 后台保活 | 省电策略激进 |
| 华为 EMUI | 通知监听权限 + 自启动 | 权限管控严格 |
| OPPO ColorOS | 后台限制 + 通知分组 | 独特通知管理 |
| vivo OriginOS | 电池优化 + 后台清理 | 定期清理后台 |
| 三星 OneUI | Deep Sleep + 后台限制 | 睡眠策略独特 |

### 8.3 关键兼容性用例

| 用例 ID | 场景 | 验证 |
|---------|------|------|
| COMPAT-001 | Android 8.0 最低版本运行 | App 正常启动，核心功能可用 |
| COMPAT-002 | Android 14 目标版本 | 所有功能正常，无权限问题 |
| COMPAT-003 | 通知监听跨 ROM | 各品牌手机通知监听正常工作 |
| COMPAT-004 | 后台存活 | 通知监听服务 1 小时内不被杀 |
| COMPAT-005 | 小屏适配 | 5" 屏幕 UI 不截断，可操作 |
| COMPAT-006 | 大屏适配 | 6.7" 屏幕布局合理 |
| COMPAT-007 | 横屏处理 | 不支持横屏的页面不旋转 |

---

## 九、测试代码示例

### 9.1 ViewModel 单元测试示例

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var viewModel: HomeViewModel
    private val parseInputUseCase: ParseInputUseCase = mockk()
    private val getTransactionsUseCase: GetTransactionsUseCase = mockk()
    private val confirmTransactionUseCase: CreateTransactionUseCase = mockk()

    @BeforeEach
    fun setup() {
        viewModel = HomeViewModel(
            parseInputUseCase = parseInputUseCase,
            getTransactionsUseCase = getTransactionsUseCase,
            confirmTransactionUseCase = confirmTransactionUseCase
        )
    }

    @Test
    fun `AI parse success should update state with results`() = runTest {
        // Given
        val mockResults = listOf(
            AiParseResult(type = "expense", amount = 3200, categoryName = "餐饮", description = "午饭")
        )
        coEvery { parseInputUseCase("午饭32") } returns Result.Success(mockResults)

        // When
        viewModel.parseInput("午饭32")

        // Then
        val state = viewModel.uiState.value
        assertThat(state.aiParseResults).isEqualTo(mockResults)
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNull()
    }

    @Test
    fun `AI parse failure should show error and suggest manual`() = runTest {
        // Given
        coEvery { parseInputUseCase("???") } returns Result.Error(5001, "AI 暂时无法理解")

        // When
        viewModel.parseInput("???")

        // Then
        val state = viewModel.uiState.value
        assertThat(state.error).isEqualTo("AI 暂时无法理解，请手动记账")
        assertThat(state.aiParseResults).isNull()
    }

    @Test
    fun `confirm all should clear results and create transactions`() = runTest {
        // Given: 有解析结果
        val items = listOf(
            AiParseResult(type = "expense", amount = 3200, categoryName = "餐饮"),
            AiParseResult(type = "expense", amount = 1500, categoryName = "交通")
        )
        coEvery { parseInputUseCase(any()) } returns Result.Success(items)
        coEvery { confirmTransactionUseCase(any()) } returns Result.Success(Unit)

        viewModel.parseInput("午饭32 地铁15")

        // When
        viewModel.confirmAll()

        // Then
        val state = viewModel.uiState.value
        assertThat(state.aiParseResults).isNull()
        coVerify { confirmTransactionUseCase(any()) }
    }
}
```

### 9.2 Room DAO 集成测试示例

```kotlin
@RunWith(AndroidJUnit4::class)
class PendingTransactionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PendingTransactionDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.pendingTransactionDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun insertAndQuery_pending() = runTest {
        // Given
        val entity = PendingTransactionEntity(
            clientId = "uuid-001",
            type = "expense",
            amount = 3200,
            categoryId = 1,
            date = "2026-07-01",
            source = "manual",
            syncStatus = "pending",
            clientCreatedAt = "2026-07-01T12:00:00",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // When
        dao.insert(entity)
        val results = dao.getAllPending()

        // Then
        assertThat(results).hasSize(1)
        assertThat(results[0].clientId).isEqualTo("uuid-001")
        assertThat(results[0].syncStatus).isEqualTo("pending")
    }

    @Test
    fun updateSyncStatus_pendingToSynced() = runTest {
        // Given
        val entity = createPendingEntity("uuid-002")
        dao.insert(entity)

        // When
        dao.updateSyncStatus("uuid-002", "synced")

        // Then
        val results = dao.getAllPending()
        assertThat(results).isEmpty() // pending 查询不到 synced 的
    }
}
```

### 9.3 Compose UI 测试示例

```kotlin
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun aiInputBox_isDisplayed() {
        composeTestRule.setContent {
            AiBillTheme {
                HomeScreen(
                    uiState = HomeUiState(),
                    onParseInput = {},
                    onConfirmItem = {},
                    onConfirmAll = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("说点什么就能记账...")
            .assertIsDisplayed()
    }

    @Test
    fun confirmCard_showsAfterParse() {
        val mockResult = AiParseResult(
            type = "expense", amount = 3200,
            categoryName = "餐饮", description = "午饭"
        )

        composeTestRule.setContent {
            AiBillTheme {
                HomeScreen(
                    uiState = HomeUiState(aiParseResults = listOf(mockResult)),
                    onParseInput = {},
                    onConfirmItem = {},
                    onConfirmAll = {}
                )
            }
        }

        composeTestRule.onNodeWithText("¥32.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("餐饮").assertIsDisplayed()
        composeTestRule.onNodeWithText("确认").assertIsDisplayed()
    }

    @Test
    fun confirmAll_button_visibleForMultipleResults() {
        val results = listOf(
            AiParseResult(type = "expense", amount = 3200, categoryName = "餐饮"),
            AiParseResult(type = "expense", amount = 1500, categoryName = "交通")
        )

        composeTestRule.setContent {
            AiBillTheme {
                HomeScreen(
                    uiState = HomeUiState(aiParseResults = results),
                    onParseInput = {},
                    onConfirmItem = {},
                    onConfirmAll = {}
                )
            }
        }

        composeTestRule.onNodeWithText("全部确认").assertIsDisplayed()
    }
}
```

---

## 十、测试环境与执行

### 10.1 本地测试命令

```bash
# 运行全部单元测试
./gradlew testDebugUnitTest

# 运行指定模块测试
./gradlew :app:testDebugUnitTest --tests "com.aibill.android.domain.*"

# 运行 Instrumented 测试（需连接设备/模拟器）
./gradlew connectedDebugAndroidTest

# 生成覆盖率报告
./gradlew jacocoTestReport

# 运行 Lint 检查
./gradlew lintDebug
```

### 10.2 CI 流水线配置

```yaml
# .github/workflows/test.yml
name: Test
on: [push, pull_request]

jobs:
  unit-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Run Unit Tests
        run: ./gradlew testDebugUnitTest
      - name: Coverage Report
        run: ./gradlew koverHtmlReport
      - name: Coverage Gate (覆盖率门禁)
        run: ./gradlew koverVerify  # 覆盖率低于目标则 CI 失败
      - name: Upload Results
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: app/build/reports/

  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Lint Check
        run: ./gradlew lintDebug
      - name: Detekt (Kotlin Static Analysis)
        run: ./gradlew detekt
      # lint 有 error 级别问题则 CI 失败

  instrumented-test:
    runs-on: ubuntu-latest
    needs: [unit-test, lint]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build APKs
        run: |
          ./gradlew assembleDebug
          ./gradlew assembleDebugAndroidTest
      - name: Auth to Google Cloud
        uses: google-github-actions/auth@v2
        with:
          credentials_json: '${{ secrets.GCP_SA_KEY }}'
      - name: Run on Firebase Test Lab
        run: |
          gcloud firebase test android run \
            --type instrumentation \
            --app app/build/outputs/apk/debug/app-debug.apk \
            --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
            --device model=Pixel6,version=33 \
            --timeout 15m
```

### 10.3 Mock 服务器

开发测试阶段使用 MockWebServer 模拟后端响应：

```kotlin
class MockApiServer {
    private val server = MockWebServer()

    fun start() {
        server.start(3000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    "/api/auth/login" -> MockResponse()
                        .setBody("""{"code":0,"data":{"token":"mock-jwt","user":{"id":1}}}""")
                    "/api/ai/parse" -> MockResponse()
                        .setBody("""{"code":0,"data":{"items":[{"type":"expense","amount":3200}]}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    fun shutdown() = server.shutdown()
    val baseUrl: String get() = server.url("/api/").toString()
}
```

---

## 十一、缺陷管理

### 11.1 冒烟测试用例（每次发版必跑）

| ID | 验证点 | 预期 |
|----|--------|------|
| SMOKE-001 | App 冷启动 | 2s 内到达首页 |
| SMOKE-002 | 登录 | 正确凭据可登录 |
| SMOKE-003 | AI 记账 | 输入"午饭32"能成功记账 |
| SMOKE-004 | 手动记账 | 填写完整能保存 |
| SMOKE-005 | 查看流水 | 有数据正确展示 |
| SMOKE-006 | 查看统计 | 图表正确渲染 |
| SMOKE-007 | 离线记账 | 断网能本地保存 |
| SMOKE-008 | 通知识别 | 微信支付通知能解析 |

### 11.2 回归测试策略

| 策略 | 说明 |
|------|------|
| Bug Fix 回归 | 每个 P0/P1 Bug 修复必须附带回归用例，纳入自动化 |
| 功能回归 | 新功能合入后，运行相关模块的全量测试 |
| 全量回归 | 发版前运行全部自动化用例（单元+集成+UI+E2E） |
| 回归用例维护 | 每月 review 回归用例集，清理过时用例 |
| Flaky Test 管理 | 连续 3 次 flaky 标记为 @FlakyTest，5 工作日内修复或删除 |

### 11.3 缺陷严重等级

| 等级 | 定义 | 响应时间 | 示例 |
|------|------|----------|------|
| P0 - 致命 | 核心功能不可用/数据丢失 | 立即修复 | 记账数据丢失、同步导致重复 |
| P1 - 严重 | 主要功能异常，有 workaround | 24h 内 | AI 解析全部失败、离线记账不生效 |
| P2 - 一般 | 次要功能异常或体验差 | 当前迭代 | 统计图表数据偶尔不准、动画卡顿 |
| P3 - 轻微 | 细微UI问题，不影响使用 | 下一迭代 | 文案错误、对齐不准、颜色偏差 |

### 11.2 测试通过标准

| 版本类型 | 通过标准 |
|----------|----------|
| 日常构建 | 单元测试 100% 通过 |
| PR 合入 | 单元 + 集成测试 100% 通过，无新增 P0/P1 |
| 发版 | 全量测试通过，P0=0, P1=0, P2≤3 |

---

*文档结束*
