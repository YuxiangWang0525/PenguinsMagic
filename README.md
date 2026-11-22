# Penguins Magic

> 依赖社区接口、高度开放的中立骚扰电话拦截工具。灵感来源于订阅代理工具，服务后端可以完全自定义。
>
> 内部代号：[Anti You](https://projectsekai.fandom.com/wiki/ANTI_YOU)

- 包名：`me.riverfrozer.penguinsmagic`
- 当前版本：1.1.2（versionCode 10102）
- 最低系统：Android 10（API 29）
- 许可：GNU GPL v3

## 为什么要做它

仓库维护者已无法忍受被某越速运的营销广告打爆手机、且要求屏蔽也不予理会的情况。一个电话不接又会漏接政务电话。走投无路，开发本项目。

这些来电要么是白号，要么是交钱解封的号码。一旦被解封，系统自带的黑名单就完全失效了——你还是拦不住。

更麻烦的是，他们不傻。拦掉一两个号码之后，立刻会有别的号码打进来，接起来是「您是 xxxx 公司的 x 总吗」。我摸清了这类电话的规律：一旦打不通，他们会按 `工作电话A → 工作电话B → 生活电话 → 集团呼叫 → 本地号码` 的顺序轮呼，间隔不超过 50 秒。

## 功能

### 订阅制后端，中立可自定义

拦截数据库以「订阅」的形式接入，形态与代理工具的订阅节点一致：填服务地址和令牌即可，可以用社区公开的后端，也可以自建，同时挂多条订阅。

每条订阅独立维护配额、错误状态与退避时间；某一条挂了不影响其他条判定。订阅可以开启「保存为策略」，拦截成功的号码会直接写进本机规则，下次同一个号码不再消耗订阅配额——这样即使后端数据被不受信任地改动，已经拦下的号码依然拦得住。

### 本机规则与号段

可以自己写下拦截条目，支持整号和号段前缀。号段的用途是拦下营销集团批量买走的一整批号码：`13000001234` 被拦一次，把 `1300000` 写成号段，同批后续号码不用再花订阅配额去问。

本机规则的优先级高于订阅判定：那是用户自己的指令，不是服务商的判断。

### 连锁封禁

拦截一两个号码拦不住轮呼，所以有了这个功能。开启并设定一个上限（1～30 分钟，默认 5 分钟）后：

| 行为 | 取值 |
| --- | --- |
| 某个号码命中拦截后的初始窗口 | 90 秒（1 分 30 秒） |
| 窗口内每再拦下一个陌生号码 | 在剩余时间上再加 30 秒 |
| 窗口内被拒接的对象 | 既不在黑名单、也不在通讯录、也不在白名单的陌生号码 |
| 窗口总时长上限 | 从这次封禁的起点算起，延长多少次都不能越过 |
| 手动「暂时放行」的有效期 | 10 分钟 |

关键的两个语义（代码里有专门的测试钉住）：延长是在剩余窗口上**加** 30 秒，不是从当前时刻重算 30 秒——后者在窗口还剩 80 秒时等于把封禁缩短；上限从**起点**算起，呼入再多次也不能把窗口推过 `起点 + 上限`。

连锁封禁命中时不查订阅、不消耗配额，直接拒接。

此时打开 Penguins Magic 可以手动临时解封、加入白名单或列入本机拦截。小建议：这时去微信搜一下这个号码，前面带广告的就列黑；确实不认识可以先暂时放行，看对方会不会用同一个号码再打回来。

### 失败一律放行

服务端抖动、超时、返回不是 JSON、配额用尽——所有异常路径都按放行处理，绝不因为后端故障误挂正常来电。实时来电查询的网络预算是 2.5 秒，单条订阅的连接/读取超时压缩到 1.5s/3s，都必须在系统给 `CallScreeningService` 的 5 秒内给出答复，且同一通电话只应答一次。

### 号码归一化

服务端不做国家码归一，所以本机按 `+86` 与国内 11 位两种写法生成候选：先查主写法，查不到再查另一种。含字母、`+` 位置非法、位数不在 5～24 位的号码按不可拨号处理，直接放行。

### 诊断页

「关于」页会如实汇报：来电筛选角色是否真的生效（小米/Redmi/POCO/HyperOS 上角色接口可能不反映实际状态，因此以系统广播和「真的处理过一通来电」作为证据）、联系人权限、电池优化豁免、通知开关、自启动页面入口、令牌存储方式、判定缓存条数，并可以一键清理缓存。

## 一通来电的判定顺序

`SpamCallScreeningService.onScreenCall` 只做静默拒接（`setDisallowCall` + `setSkipNotification`），拦截提醒由本应用自己发出，带上类型与来源。

1. 总开关关闭 / 号码为空 → 放行
2. 白名单、连锁封禁的「暂时放行」 → 放行（用户点名要放进来，压过下面一切）
3. 本机规则命中 → 拒接
4. 通讯录比对（需 `READ_CONTACTS`） → 放行。比对放在 IO 协程里而不是主线程回调，因为 provider 查询耗时不可控，会吃掉 5 秒预算
5. 连锁封禁窗口内的陌生号码 → 拒接并延长窗口
6. 订阅查询：先吃判定缓存，再向所有已启用的订阅**并行**问一次 `getBlockInfo`，任一命中即拦截；全部失败或超预算则放行

## 订阅后端接口契约

本项目只依赖两个接口，自建后端实现这两个即可接入。明文 HTTP 是允许的（`network_security_config` 已放开），因为后端本就由用户自选。

### `GET /health`

响应 `{"success": true, "data": {...}}`，`data` 中可读字段：`name`、`version`、`database`、`tokenHeader`。`tokenHeader` 指出查询接口要用的请求头名称，缺省为 `x-auth-token`；用户也可以在订阅里手写覆盖。

### `GET /getBlockInfo?number=<号码>`

号码经 URL 编码（`+` 会被编码成 `%2B`，裸写会被服务端解码成空格），令牌放在上述请求头里。响应 `data` 字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `number` | string | 服务端归一后的号码，为空时回退为查询值 |
| `hit` | boolean | 是否命中 |
| `action` | string | `block` 同样判为拦截；`blocked = hit \|\| action == "block"` |
| `inDatabase` | boolean | 号码是否在库中 |
| `reason` | string | 判定原因，`in_database_but_not_enabled` 表示在库但用户未启用该类型 |
| `rule` | object | `typeId`、`typeName`、`blockWholeType`、`subtagId`、`subtagName`、`subtagKind`、`brand`、`scamCategory`；`subtagKind == "scam"` 时界面标记为诈骗 |
| `quota` | object | `hourlyLimit`、`usedInCurrentHour`、`remaining`。不限制时服务端把 `remaining` 写成 `null`，客户端区分「未知/不限」与 0 |

状态码按语义映射为失败类型：400 号码格式、401/403 令牌与账户（需要用户重新配置）、404 接口不存在、429 每小时上限（触发对齐整点的退避）、5xx 服务端错误、其余以及非 JSON / 缺少 `data` / `success=false` 为协议错误。

## 权限与厂商适配

| 权限 | 用途 |
| --- | --- |
| `ANSWER_PHONE_CALLS` | 静默拒接；随「来电筛选应用」角色一并授予 |
| `READ_CONTACTS` | 只在本机比对来电号码认出熟人放行。不授予时系统根本不会把联系人的来电交给我们 |
| `POST_NOTIFICATIONS` | Android 13 起通知是运行时权限，拦截成功后要能提醒 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 省电策略会冻结后台、把筛查服务在两次来电之间杀掉 |
| `RECEIVE_BOOT_COMPLETED` | 开机与升级后复查资格——厂商系统可能把默认筛选应用改回去 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 查询订阅后端 |

没有声明悬浮窗、辅助功能、`READ_CALL_LOG` 或任何厂商私有权限。小米/MIUI/HyperOS 的适配是纯代码逻辑：识别机型后走自启动设置页与专门的授权引导，并在 `RESULT_OK` 之后重新校验真实状态（MIUI 可能返回成功但实际没授予）。

## 数据与隐私

- 业务数据是应用私有目录下的 JSON 文件，经防抖写入：`subscriptions.json`、`block_rules.json`、`whitelist.json`、`history.json`、`number_cache.json`、`chain_ban.json`；设置项走 `SharedPreferences`。没有使用 Room 或 DataStore。
- 订阅令牌用 Android Keystore 的 AES-GCM 加密保存；Keystore 异常时按用户确认过的降级方案存入私有目录，该情况会在「关于」页标黄提示。令牌永不写入日志，也不参与备份（`allowBackup="false"`）。
- 拦截历史仅记录号码、类型、来源与时间，全部留在本机。

## 项目结构

```
app/src/main/java/me/riverfrozer/penguinsmagic/
├── App.kt                      应用入口，装配仓储单例
├── MainActivity.kt             单 Activity + Navigation 抽屉，持有授权流程
├── screening/                  CallScreeningService、判定引擎、角色与状态、开机复查、通知、厂商引导
├── data/                       模型、各仓储、号码归一化、连锁封禁策略、判定缓存、令牌存储、JSON 读写
├── net/                        HttpURLConnection 客户端、接口模型与失败分类、连通性诊断
└── ui/                         History / Subscriptions / BlockRules / Whitelist / About 各 Fragment 与 Adapter
```

界面是 Kotlin + Views（XML 布局、ViewBinding、Navigation），不是 Compose；UI 文案目前只有中文。第三方依赖只有 AndroidX、Material、Navigation、RecyclerView 和 `org.json`，没有网络与数据库框架。

## 构建

要求 JDK 11 以上的 Android Studio / Gradle 环境，Gradle Wrapper 为 9.5.0，AGP 9.3.3。

```bash
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:testDebugUnitTest    # 单元测试
./gradlew :app:assembleRelease      # 发布包
```

发布签名从仓库根的 `keystore.properties` 读取（该文件与 keystore 本体都已在 `.gitignore` 中），缺失时退回默认签名。文件形如：

```properties
storeFile=riverfrozer.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

release 构建关闭了代码优化与混淆。

## 测试

`app/src/test/` 下有 12 个 JVM 单元测试、59 个用例，覆盖连锁封禁时间算术、接口响应解析与失败分类、号码归一化与候选写法、号段规则匹配、通讯录比对、令牌存储、通知文案、manifest 与导航图的结构校验。instrumented 测试只有默认模板那一个。

单元测试跑在 JVM 上，能证明时间算术与解析逻辑正确，但**不能**证明字段为空时的界面表现或各厂商系统的权限行为——那部分需要在真机上验证。本项目的日常验证设备是小米 / HyperOS。

## 许可

本应用在 GNU General Public License version 3 协议下发行，详见 [LICENSE](LICENSE)。
