# Li 跳过（AdSkipper）

一个自用的 Android 广告跳过工具，思路类似「李跳跳」：靠无障碍服务读节点树、点「跳过 / 关闭」。
但匹配逻辑换成了**多因子打分**，并且加了「**点一下就学会**」的规则拾取器，冷门 App 也能几秒适配。

纯本地运行，不联网、不上传截图、不申请任何网络权限。

---

## 它和传统做法的区别

| | 传统做法 | 本项目 |
|---|---|---|
| 判断方式 | 一条条 if：文本等于「跳过」就点 | 文本 / id / 描述 / 类名 做硬条件，位置、尺寸、可点击性、开屏时机、像素颜色做加权打分，≥ 阈值才动手 |
| 冷门 App | 等作者更新规则库 | 自己点一下就生成规则，不用写代码 |
| 误触 | 容易点到「跳过片头」这类正常按钮 | 尺寸上限 + 区域约束 + 点击前二次确认 + 冷却 |
| 无节点树的广告（H5 / WebView） | 没辙 | 像素探针 + 位置规则兜底 |

## 快速开始

1. 装 APK（GitHub Actions 里下载 `LiSkip-debug-apk`）
2. 打开 App → 点「去开启无障碍服务」→ 找到「Li 跳过」打开
3. 建议顺手关掉电池优化，否则某些系统上服务会被杀
4. 默认已经带 4 条通用规则，开屏广告基本能直接跳过

## 三种加规则的方式

**① 拾取（推荐，冷门 App 用它）**
点「拾取规则」→ 屏幕上出现一个小球 → 切到目标 App、走到广告页 → 轻点小球 → 轻点那个「跳过」按钮。
自动生成规则：文字、viewId、相对位置、尺寸上限、包名全部填好。小球可拖动，长按取消。

**② 手动新建**
适合批量、适合调阈值。可写的条件：文字关键词 / 文字正则 / viewId 正则 / 描述正则 / 类名 / 区域 / 尺寸 / 最低分 / 动作 / 冷却 / 像素探针。

**③ 导入导出**
规则是一坨 JSON，可导出备份、改完再导入，也可以手动编辑 JSON 批量塞进去。

## 规则字段速查

```jsonc
{
  "name": "某App 开屏",
  "packages": ["com.example.app"],   // 空数组 = 全局
  "onlySplash": true,                // 只在应用刚启动 9 秒内生效
  "activityOnly": false,             // true = 只看 Activity，不看节点树
  "activityRegex": "openadsdk|pangle",
  "keywords": ["跳过", "skip"],       // 硬条件，任一命中
  "keywordMode": "CONTAINS",         // CONTAINS | EXACT | REGEX
  "textRegex": "跳过\\s*\\d+",
  "viewIdRegex": ".*skip.*",
  "descRegex": "关闭",
  "classNameContains": "TextView",
  "region": { "l": 0.4, "t": 0, "r": 1, "b": 0.35 },  // 归一化屏幕坐标
  "maxWidthFrac": 0.45,              // 节点宽度不能超过屏幕宽的 45%
  "maxHeightFrac": 0.16,
  "mustClickable": true,
  "allowAncestorClickable": true,
  "allowPositional": false,          // 允许没有任何文本条件的纯位置规则
  "minScore": 75,
  "action": "CLICK",                 // CLICK | CLICK_PARENT | PRESS_BACK | SWIPE_UP
  "cooldownMs": 1500,
  "probe": { "x": 0.9, "y": 0.1, "color": 16777215, "tol": 48 }
}
```

### 打分规则（`MatchEngine`）

- 硬条件命中：base 50（viewId 45 / desc 40 / 类名 30，取最大值）
- 尺寸在限制内：+15；超出 → **直接判负**
- 节点可点击或祖先可点击：+10；都不行且要求可点击 → **判负**
- 落在 `region` 内：+25，落在外面 −20
- 开屏期间：+5
- 文案超过 8 个字：−10
- 像素探针匹配：单独校验，不匹配直接放弃

默认阈值 75，所以「右上角 + 小尺寸 + 可点击 + 带跳过字样」这种典型开屏按钮能拿到 100 分，
而页面中间那个「跳过片头」（尺寸大、不在区域内）拿不到分。

## 从源码编译

### 用 GitHub Actions（不用装 Android Studio）

仓库已带 `.github/workflows/android.yml`，push 到 `main` 就会自动构建：

1. 代码推到 GitHub
2. 打开仓库 → **Actions** → `Build APK` → 最新一次运行
3. 页面底部 **Artifacts** 下载 `LiSkip-debug-apk.zip`，解压得到 `app-debug.apk`

`workflow_dispatch` 已开启，也可以在 Actions 页面手动点 **Run workflow**。

### 本地编译

- Android Studio（Ladybug 或更新），JDK 17
- 直接 Open 本目录，等 Gradle 同步完 → Run
- 若提示缺 Gradle Wrapper，在项目根目录执行 `gradle wrapper --gradle-version 8.7`，或让 AS 用本地 Gradle 分发

## 目录结构

```
app/src/main/java/com/li/tiaotiao/
├── App.kt
├── core/
│   ├── Rule.kt              # 规则数据模型 + JSON 序列化
│   ├── RuleStore.kt         # 规则持久化、导入导出、默认规则
│   ├── MatchEngine.kt       # 多因子打分
│   ├── SkipService.kt       # 无障碍服务主循环
│   ├── Picker.kt            # 悬浮球拾取器
│   ├── Screenshotter.kt     # 截图 + 像素取色
│   ├── AdSdkSignatures.kt   # 广告 SDK Activity 特征库
│   ├── Settings.kt
│   └── LogBus.kt
└── ui/
    ├── MainActivity.kt
    └── RuleEditorActivity.kt
```

## 已知限制

- 纯图片按钮（无文字、无 id、无描述）只能靠「区域 + 尺寸 + 像素探针」，或者开启 `allowPositional`
- Flutter / Unity / 部分 WebView 广告不暴露子节点，节点树里只有一个大容器，此时位置规则更容易误触，建议配合像素探针
- 无障碍服务本身会被部分 App 检测（银行、支付类通常直接禁用），这类 App 就别开了
- 个人自用工具，请勿上架应用商店；跳过广告属于绕过他人商业模式，自行承担使用风险
