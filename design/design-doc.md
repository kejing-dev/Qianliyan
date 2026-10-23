# Spatial App Design Doc — 借眼 Jieyan（远程协助）

> 状态：设计包 **v2**（2026-10-19）。v1 的"一画即冻结 + 粉笔板"被用户否决（**画面不冻结**），v2 改为实时视频 + 视野玻璃。范围：设计文档 + 一个可点的 Web 原型（`preview.html`，`web_design_validation_only`）。不含运行时代码、设备证据。

> **2026-10-21 · 设计包 v3（应用 v0.2.0）**：新增「真实画面（SpatialML 读回）+ 双向语音」，变化处全部写在 `v3-vst-voice.md`（C1–C13）；本文其余部分仍有效，与 v3 冲突处以 v3 为准（如：v1 不做音频 → v3 做；通话条 5 键 → 6 键；配对卡 640×384 → 640×448）。

> 术语：Full Space / Stage Mixed（透视）、Planar 面板。实现枚举（`STAGE_MIXED` 等）由下游 `spatial-design-to-app` 决定。UI 全部用 PICO SpatialUI（`PicoTheme` + 内置组件），字体 Noto Sans。

**配套文件** — `ux-flow.html`：UI/UX 流程图（启动 → 配对 → 看见 → 指点 → 异常与结束，头显/电脑/手机三种画面）；`networking.md`：网络与视频链路方案与可行性；`preview.html`：可点原型。

## 0 · 一句话

戴 Swan 的现场人员把眼前的实时画面分享给电脑前的专家；专家直接在视频上画，笔迹同一时刻出现在现场人员眼前一块跟着视野走的透明玻璃上，位置和专家画的地方对得上；现场人员也能在同一块玻璃上画，专家的视频上同步看到。

## 1 · Frame

**Subject** — 远程协助（Remote Assist / "see-what-I-see"）。谱系：Microsoft Dynamics 365 Remote Assist、TeamViewer Assist AR、Vuforia Chalk、FaceTime/Zoom 共享标注。v2 取的是 **Zoom/FaceTime 一派**的模型：标注直接落在实时视频上、不冻结、随时清除；空间化的部分是"把这层标注原位放回现场人员的视野里"。

**Viewer & context**
- **现场人员**（Swan，Full Space 透视）：站着或弯腰，双手在干活（修机器、装设备、给数采台架布线、操作遥操作系统），眼睛在 0.5–1.5 m 的工作面上。只能用眼手 / 手柄短暂点一下。
- **专家**（电脑浏览器，LAN 内网页）：坐着，鼠标，大屏幕，能说话（语音走并行的电话 / 飞书会议，v1 不做音频）。

**The single job** — 让专家能指着现场人员视野里的**那一个点**说"这个"，并且这个"指"同一时刻落在现场人员眼前对应的位置，双手不离开活。

**Spatial justification** — 用户给定：Full Space；画面不冻结；标注显示在平面上即可。

| Core task | Affordance | Why spatial beats flat | 2D counterfactual |
| --- | --- | --- | --- |
| 看专家画了什么 | **Direction**（玻璃与相机视野同向、同视场）+ **Body**（免手持） | 专家在视频上圈红灯，现场人员看过去圈就在红灯上——笔迹沿着相机的同一条视线回到眼前；两只手都空着 | 手机/平板举在手里看带标注的视频：要腾一只手，而且标注在手里的屏幕上，不在眼前的物体上 |
| 专家实时"指"一下 | **Direction** | 鼠标悬停处直接以一个黄点出现在现场人员视野里，不需要任何操作 | 口头描述"左边第二个螺丝" |
| 现场人员回画 | **Collaboration**（同一块玻璃、同一坐标系） | 双方画在同一层，坐标归一化到相机画面，谁画的一眼分清（黄/蓝） | 两边各自截图、各自标注、互发 |
| 隐私/知情 | — | 共享中通话条常显红点 | 同样需要 |

这里明确接受的取舍：玻璃是**头锁定**的，头一转，笔迹跟着视野走而不是留在物体上——专家那边的视频也一样转，两边看到的东西始终一致，这是"不冻结"模型的自然结果。要让笔迹贴在物体上需要深度 + 锚点（v2 方向 C），本次不做。

**Assumptions**

| Assumption | Confidence | What changes if wrong |
| --- | --- | --- |
| Swan 上应用侧拿摄像头像素的路径是 SpatialML Readback Mode（`rectifiedVSTAccess` → 全局 dynamic texture → `readbackContent`，需 `CAMERA` 权限）。6.1 文档没有写 Android Camera2 取 VST 的方式 | 高（文档明确），Camera2 **未确认** | 若 Camera2 可用，视频管线更简单，设计不变 |
| 模拟器上 `rectifiedVSTAccess` 不出真实画面（文档明确）。应用内置"合成相机源"用于模拟器验证同步回路；真实画面只能真机验证 | 高 | 无 |
| 512×512（或按真机比例）RGBA 读回 + JPEG 编码能跑到 10–15 fps、端到端 < 300 ms（LAN） | 中 | 降到 8 fps / 384 px；或改 WebRTC H.264（v2） |
| 视野玻璃的尺寸可以由 `rectifiedVSTAccess` 返回的相机内参（`cameraMatrixResult`，3×3）算出：放在 1.0 m 处时宽 = W/fx，高 = H/fy，这样笔迹沿相机视线回到眼前 | 中（内参可得，右眼相机与头中心有几厘米偏置，1 m 处的视差 ≤ 2°） | 退化为手调的固定视场；或加一次手动对齐 |
| 头锁定面板在 Stage 里可做：每帧按 HMD 位姿更新面板实体的 transform（ECS 系统），或用系统头锁定附着 | 中（具体 API 待下游用 pico-dev-knowledge 确认） | 退化为懒跟随玻璃，转头时笔迹短暂偏离 |
| 专家与头显在同一 Wi-Fi，头显起 HTTP+WebSocket 服务，专家浏览器打开 `http://<ip>:8765` + 4 位 PIN | 高 | 跨网需要中继（v2） |
| Stage 里 `AlertDialog` 不显示内容、`SnackbarHost` 不可用（空间尺项目实测） | 高 | headset 侧确认改为通话条内联两步；提示用通话条状态文字 |
| 笔迹默认一直留着直到清除；专家台有"8 秒自动淡出"开关（默认关） | 中（产品判断） | 可改默认 |

## 2 · Explore — alternatives

**Direction generation** — inline-isolated（用户对 token 消耗敏感、要求 agent 数量克制）。v1 的方向 A0（一画即冻结的粉笔板）已被用户否决，记录在案，不再参评。

### Direction A — 视野玻璃（Visor Glass）

- **概念**：现场人员眼前 1.0 m 处有一块看不见的透明玻璃，大小正好等于相机视野，跟着头走。专家在实时视频上画什么，玻璃上同一位置就出现什么；专家鼠标悬停就是一个黄点。现场人员打开画笔后，玻璃显出一圈细框，可以用捏合拖动在上面画蓝色笔迹，专家视频上同步出现。笔迹一直留着，任一方一键清除；专家可开"8 秒淡出"。平时视野里只有一条小通话条。
- **签名**：**专家的粉笔落在你眼前的玻璃上，位置就是他在视频上画的位置。**
- **主要风险**：玻璃尺寸/视场要和相机内参对齐，否则笔迹和实物错位；头锁定面板在 Stage 里的实现路径待确认。

### Direction B — 镜面板（Mirror Board）

- **概念**：现场人员面前常驻一块大 Planar 板，实时显示专家看到的视频 + 笔迹（把专家的屏幕镜像到空中）。
- **签名**：零对齐问题，"专家看到什么我看到什么"。
- **主要风险**：把眼前的东西再在空中看一遍是冗余，大板挡住工作面；本质是悬浮显示器，放回 2D 不变差——过不了 2D 反例。

### Direction C — 贴到实物上的粉笔（Chalk in the Room）

- **概念**：每一笔通过 `getDepthMap` + `uvTo3DInCameraSpace` 投到真实深度再挂到空间锚点，笔迹贴在被指的螺丝上，转头也不跑。
- **签名**：HoloLens Remote Assist 同级体验。
- **主要风险**：深度质量、遮挡、投影误差只能真机验证；模拟器没有深度和相机；工作量是 A 的 2–3 倍。

**Decision matrix**（5 高 / 1 低）

| Direction | Task efficiency | Spatial value | PICO comfort | Domain fit | Risk | Distinctiveness |
| --- | --- | --- | --- | --- | --- | --- |
| A 视野玻璃 | 5 | 3 | 4（透明、只有一条小条） | 5 | 中 | 4 |
| B 镜面板 | 3 | 1 | 2（大板常驻、挡手） | 3 | 低 | 1 |
| C 贴实物 | 5 | 5 | 3 | 5 | 高 | 5 |

**Chosen: A** — 在"不冻结、显示在平面上"的范围里，唯一让笔迹回到实物方向上的方案；实现路径清楚（内参定尺寸 + 头锁定），和 C 共享相机内参这一步。
**Rejected** — B：2D 反例站不住，且常驻大板违反"不挡工作面"；C：超范围、不可在模拟器验证、工作量超预算，列为 v2。

## 3 · Plan

### Visual tokens

主题前提：SpatialUI `vibrant` + 根面 `Material.Regular` 玻璃；字体 Noto Sans。颜色来自题材：**粉笔与石板**——专家的黄粉笔、现场人员的蓝粉笔、摄像头的直播红点、专家台的深石板。

| `ColorScheme` role | 值 | 用途 |
| --- | --- | --- |
| `fillPrimary` | `inherit SpatialUI Vibrant fillPrimary` | 图标按钮默认底 |
| `fillSecondary` | `inherit SpatialUI Vibrant fillSecondary` | 次级卡片 |
| `fillTertiary` | `inherit SpatialUI Vibrant fillTertiary` | 分段控件底 |
| `fillLight` | `inherit SpatialUI Vibrant fillLight` | 输入框底 |
| `labelPrimaryLight` | `inherit SpatialUI Vibrant labelPrimaryLight` | 图标与按钮文字（白） |
| `labelPrimary` | `inherit SpatialUI Vibrant labelPrimary` | 正文 |
| `labelSecondary` | `inherit SpatialUI Vibrant labelSecondary` | 次级文字 |
| `labelTertiary` | `inherit SpatialUI Vibrant labelTertiary` | 辅助文字 |
| `labelQuaternary` | `inherit SpatialUI Vibrant labelQuaternary` | 占位文字 |
| `lightenHover` | `inherit SpatialUI Vibrant lightenHover` | 悬停 |
| `lightenPressed` | `inherit SpatialUI Vibrant lightenPressed` | 按下 |
| `error` | `#FF5F52` | 权限被拒、PIN 错误、画面不可用 |
| `alert` | `#FF8A1F` | 重连中、画面过期、已暂停 |
| `passable` | `#7CF2A3` | 已连接、已授权 |
| `interaction` | `#FFE14D`（粉笔黄） | 选中态、主按钮（连接）；其上内容用 `inkOnChalk` |
| `dividerLine` | `inherit SpatialUI Vibrant dividerLine` | 分隔线 |

**Brand / decorative tokens outside `ColorScheme`**

| Token | Hex | 用途 |
| --- | --- | --- |
| `chalkYellow` | `#FFE14D` | 专家笔迹、专家指点光标 |
| `chalkWhite` | `#F6F2E6` | 专家可选笔色 2 |
| `chalkPink` | `#FF8FB1` | 专家可选笔色 3 |
| `chalkBlue` | `#8CD5FF` | 现场人员笔迹（固定，不可选）；画笔开启时的玻璃细框 |
| `inkOnChalk` | `#1E1A0E` | 放在粉笔黄上的图标/文字 |
| `liveRed` | `#FF3B30` | 共享中红点（REC 语义） |
| `slate` | `#20262A` | 专家台页面底色（石板） |
| `slateDeep` | `#161B1E` | 取景框黑边 |
| `strokeHalo` | `rgba(0,0,0,.45)` | 笔迹外描边，保证在亮画面/透视里可读 |

**Semantic colors**

| Status | 颜色 | 冗余线索 | UI 文案 |
| --- | --- | --- | --- |
| 共享中 | `liveRed` 点 | 实心圆点 + 呼吸 | 直播 / LIVE |
| 已连接 | `passable` | 实心小圆 | 专家已连接 |
| 等待 | `labelSecondary` | 空心圆 | 等待专家接入 |
| 对方画笔开 | `chalkBlue` 芯片 | ✎ 图标 | 对方画笔已开 |
| 重连中 | `alert` | ↻ 图标 + 线性进度 | 重连中… |
| 已暂停共享 | `alert` | ⏸ 图标 | 已暂停共享 |
| 画面过期 | `alert` | 灰化 + 时间 | 画面 3 秒前 |
| 错误 | `error` | ⚠ 图标 | 摄像头权限未授权 / PIN 错误 / 画面不可用 |

**Type** — Noto Sans（应用内打包 Noto Sans + Noto Sans SC，与 Perch / 空间尺一致）。`displayLarge` 40/54 Bold 用于 PIN（字距 +8）；`titleLarge` 20/26 SemiBold 用于地址；`titleMedium` 16/20 用于卡片标题；body `bodyMedium` 14/18 Medium；`labelMedium` 12/16 SemiBold 用于芯片、时间戳。中文 ≤17sp 一律 Medium。

**Materials / depth** — 通话条、配对卡：根面 `Material.Regular`；**视野玻璃无材质**（纯透明），只有现场人员画笔开启时显出 2 dp 的 `chalkBlue` 细框和 4% 白色薄纱，提示"这里可以画"；笔迹层带 `strokeHalo` 外描边。通话条内联确认时条体前移 +8 dp。深度语言：玻璃在 1.0 m，通话条在 0.9 m 稍近稍低，不互相遮挡。

### Signature element

**专家的粉笔落在你眼前的玻璃上，位置就是他在视频上画的位置。** 其余一切都收着：通话条只有图标和一行状态；指点只是一个点；专家台的工具盘只有三个色块、三档粗细、撤销、清除、淡出开关，没有文字。

### Surface sizing

| Window / surface | Type | Content | Default | Min | Max |
| --- | --- | --- | --- | --- | --- |
| 通话条 Call Strip | Stage 内 Planar 面板，懒跟随，视野下沿 0.9 m | 状态 + 5 个 56 dp 图标键 | 616×112 dp（物理约 0.38×0.07 m） | 520×112 | 760×112 |
| 配对卡 Lobby Card | Stage 内 Planar 面板，正前 1.2 m | 地址、PIN、权限行、说明 | 640×384 dp（物理约 0.56×0.34 m） | 560×340 | 720×420 |
| 视野玻璃 Visor Glass | Stage 内 Planar 面板，**头锁定**，1.0 m，尺寸 = 相机视场在 1.0 m 处的投影 | 笔迹层 + 指点光标 + （画笔开时）细框 | 1280×720 dp 渲染分辨率；物理 W/fx × H/fy（估计约 1.05×0.60 m ≈ 55°×33°） | 按内参 | 按内参 |
| 专家台 Expert Console | 浏览器页面 | 顶栏 56 + 取景框 16:9 + 工具盘 | 视口 1440×900 | 1024×640 | 任意 |

### Layout

**Headset（Stage Mixed）**

```
   透视的真实房间                                  ┌ 视野玻璃（看不见，画笔开时显细框）┐
                                                  │                                  │
          [监视器]      [机械臂]       ┌─────┐     │   ○ 专家画的圈（黄）               │
                                      │CTRL │     │        ● 专家光标                 │
                                      │ ●●●●│     │   ╲ 我画的线（蓝）                 │
   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━   └──────────────────────────────────┘
              ┌──────────────── 通话条 ────────────────┐
              │ ● 直播  专家已连接    ✎   ↶   🗑   ◉   ⏻   │
              └─────────────────────────────────────────┘
```

**专家台（浏览器）**

```
┌ 借眼·专家台   [● 直播]  86 ms·14 fps                                  [结束] ┐
│   ┌──────────────────── 取景框 16:9 ─────────────────────┐  [✎ 对方画笔已开]  │
│   │                                                        │                   │
│   │          实时画面 + 双方笔迹 + 专家光标                   │                   │
│   │                                                        │                   │
│   │   "直接在画面上画，现场人员立刻看到 · 悬停即指点"          │                   │
│   └────────────────────────────────────────────────────────┘                   │
│        ┌ [● ● ●]  [· • ●]  │  ↶  清除  │  8 秒淡出 (○) ┐                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

单一焦点：取景框 / 视野玻璃。密度上限：工具盘 ≤ 6 个控件；通话条 ≤ 5 个键 + 1 行状态。

### State graph

会话状态**双端共享**（同一条 WebSocket 上的同一状态机），按 headset 视角写，专家台同步显示对应态。

| State | Primary task | Primary focus | Components | Data deps | Entry/exit |
| --- | --- | --- | --- | --- | --- |
| 配对 Lobby | 让专家连上 | 配对卡（地址 + PIN） | 配对卡、权限行 | 本机 IP、PIN、相机权限状态 | 入：启动 / 结束会话；出：专家 PIN 正确 |
| 配对·权限被拒 | 授权相机 | 权限行（error） | 配对卡 + [授权] | 权限结果 | 入：拒绝；出：授权成功 → 配对 |
| 直播 Live | 干活，看专家指哪 | 真实世界 + 视野玻璃上的笔迹/光标 | 通话条、视野玻璃 | 视频流、pointer、strokes | 入：配对成功 / 共享恢复 / 重连成功；出：暂停 / 断线 / 结束 |
| 直播·我在画 | 回画 | 视野玻璃（细框显出） | 通话条（✎ 选中、↶ 可用）、玻璃 | strokes | 入：✎ 开；出：✎ 关 |
| 已暂停共享 Paused | 隐私 | 通话条（◌） | 通话条 | — | 入：现场人员关共享；出：再开 |
| 重连中 Reconnecting | 等 | 通话条（↻ + 进度） | 通话条 | socket 状态 | 入：socket 断；出：重连成功回前态 / 30 s 超时回配对 |

| Transition | From → To | Trigger | Explicit confirm? |
| --- | --- | --- | --- |
| T1 接入 | 配对 → 直播 | 专家输入正确 PIN | 否 |
| T2 专家画 | 直播 | 专家在取景框按住拖动 | 否 |
| T3 现场人员画 | 直播·我在画 | ✎ 开后在玻璃上捏合拖动 | 否 |
| T4 指点 | 直播 | 专家鼠标悬停取景框 | — |
| T5 撤销 | 直播 | 专家 ↶ / Ctrl+Z 撤自己上一笔；现场人员 ↶ 撤自己上一笔 | 否 |
| T6 清除 | 直播 | 任一方 🗑 清掉全部笔迹 | 否（专家台 Snackbar 可撤销；笔迹是即时内容，不是数据） |
| T7 自动淡出 | 直播 | 专家台开关开：每笔画完 8 s 后 2 s 内淡出 | 否 |
| T8 暂停/恢复共享 | 直播 ↔ 已暂停 | 现场人员按 ◉/◌ | 否（LIVE 点立即熄灭） |
| T9 断线 / 重连 / 超时 | 直播/暂停 → 重连中 → 前态 / 配对 | socket 断 / 恢复 / 30 s | — |
| T10 结束会话 | 任意 → 配对（新 PIN） | 通话条 ⏻ / 专家台 [结束] | **是** |
| T11 权限 | 配对 ↔ 权限被拒 | 系统权限结果 | — |
| T12 画面不可用 / 过期 | 直播（fallback/error） | 读回失败 / 连续 3 s 无帧 | — |

### Components

- **通话条 Call Strip** — 任务：一眼知道状态、一指开画笔/撤销/清除/隐私/结束。数据：连接态、共享态、画笔态、是否有笔迹。状态：配对（扩展成配对卡）/ 直播 / 我在画 / 暂停 / 重连 / 内联确认（结束）。
- **配对卡 Lobby Card** — 任务：让专家连上。数据：`http://<ip>:8765`、PIN、权限状态。状态：等待 / 权限被拒。
- **视野玻璃 Visor Glass** — 任务：承载双方笔迹与专家光标。数据：strokes（归一化坐标、作者、颜色、粗细、结束时间）、pointer、相机内参（定尺寸）。状态：隐形 / 画笔开（细框）/ 淡出中。
- **专家台·PIN 门 Gate** — 任务：连接。数据：PIN；错误：PIN 错 / 会话已结束 / 连接已断开。
- **专家台·取景框 Viewfinder** — 任务：看与画。数据：视频帧、strokes、光标。状态：直播 / 对方已暂停 / 画面过期 / 画面不可用 / 重连中；角标：对方画笔已开。
- **专家台·粉笔盘 Chalk Tray** — 任务：画。只有一种笔（线）。控件：三个颜色色块（黄/白/粉，用色块不用文字，选中显 ✓）、三档粗细（细/中/粗，用点的大小表示，选中用粉笔黄底）、撤销、清除、8 秒淡出开关。
- **专家台·状态签 Status Pill** — 数据：会话态、延迟、fps。

### SpatialUI Web component map

| Planned element | SpatialUI tag | API checked in | Public event/state | Custom fallback reason |
| --- | --- | --- | --- | --- |
| 通话条图标键（✎/↶/🗑/◉/⏻） | `sui-toggle-icon-button`（✎、◉）、`sui-icon-button`（↶、🗑、⏻） | `js/icon-button.js`, `js/advanced-controls.js` | `checked-change`、`click-action` | n/a |
| 通话条状态文字 | `sui-text` | `js/text.js` | `variant` | n/a |
| 直播红点 | `sui-dot-badge` | `js/badge.js` | `color` | n/a |
| 内联确认（是/否） | `sui-button` ×2 | `js/button.js` | `click-action` | n/a（Stage 内不用浮层对话框） |
| 配对卡 [授权] | `sui-button` | `js/button.js` | `click-action` | n/a |
| 视野玻璃笔迹层 / 光标 / 细框 | 自定义 `<svg>` / `<div>` | — | pointer 事件 | 领域内容：矢量笔迹与空间指示，不是控件 |
| PIN 输入 | `sui-text-field` | `js/text-field.js` | `value-change`、`error` | n/a |
| 连接 / 结束 | `sui-button` | `js/button.js` | `click-action` | n/a |
| 颜色色块 ×3 | `sui-icon-button`（`container-color` = 粉笔色，选中显 ✓ 并加白环） | `js/icon-button.js` | `click-action` | n/a |
| 粗细三档 | `sui-icon-button` ×3（点图标，选中用 `interaction` 底） | `js/icon-button.js` | `click-action` | n/a |
| 撤销 / 清除 | `sui-icon-button` | `js/icon-button.js` | `click-action` | n/a |
| 8 秒淡出 | `sui-switch` | `js/switch.js` | `checked-change` | n/a |
| 状态签 / 对方画笔芯片 / 画面过期芯片 | `sui-chip` | `js/chip.js` | `label` | n/a |
| 重连进度 | `sui-linear-progress`、`sui-circular-progress` | `js/progress.js` | `indeterminate` | n/a |
| 结束会话确认（专家台） | `sui-alert-dialog` + `sui-button` | `js/dialogs.js` | `dismiss-request`、`click-action` | n/a |
| 清除撤销提示（专家台） | `sui-snackbar-host` | `js/feedback.js` | `show()`、`snackbar-result` | n/a |

### 实现架构（给下游的事实，不是本包的代码）

```
 Swan (Kotlin, Spatial SDK 6.1.9, Stage Mixed, SpatialUI + Noto Sans)          专家电脑（浏览器）
 ┌──────────────────────────────────────────────────────┐                     ┌──────────────────────┐
 │ SpatialML session (Readback Mode, 512×512 或真机比例)  │   WebSocket /ws     │ 专家台页面            │
 │  rectifiedVSTAccess(right, cameraMatrix) → RGB→RGBA   │ ── JPEG 帧 ≤15fps ──▶│  取景框 <canvas>     │
 │  → global dynamic texture → readbackContent           │ ◀─ JSON 笔迹/光标 ──▶│  粉笔盘              │
 │  → JPEG 编码（工作线程）                               │                     │                      │
 │ Ktor 内嵌服务 :8765  GET /（页面来自 assets）           │ ◀── HTTP ───────────│                      │
 │ 会话状态机（权威）、PIN、strokes 列表                    │                     └──────────────────────┘
 │ SpatialUI 面板：通话条 / 配对卡                         │
 │ 视野玻璃：头锁定面板实体，ECS 系统每帧按 HMD 位姿写 transform；│
 │   尺寸由 cameraMatrix 内参算（1.0 m 处 W/fx × H/fy）     │
 │ 模拟器：合成相机源（循环图序列 + 时间戳）+ 固定内参        │
 └──────────────────────────────────────────────────────┘
```

- 消息：`hello{pin}` / `state{…}` / `frame`(binary JPEG) / `stroke.begin|point|end{strokeId, owner, color, width, pts[]}` / `undo{strokeId}` / `clear` / `pointer{x,y}`（≤20 Hz）/ `pen{on}` / `fade{on}` / `share{on}` / `end` / `ping|pong`。坐标统一为相机画面的归一化 0–1；玻璃上同一归一化坐标即同一条视线。
- 笔迹渲染：headset 侧用 Compose `Canvas` 画在玻璃面板上（低频 UI 内容，不走 ECS 每帧）；头锁定 transform 走 ECS 系统。
- 权限与清单：`CAMERA`（读回）、`INTERNET`；LAN 内、PIN 门；共享中 headset 常显红点。
- 生命周期：退后台 / 关容器 → 停止读回、关 socket、停服务器、清 strokes。
- 真机才能验证：VST 读回帧率与延迟、画面比例、内参取用与玻璃对齐误差、头锁定手感。模拟器可验证：配对、双向笔迹、撤销/清除/淡出、暂停、重连、确认流。

## 4 · Critique (before build)

**Generic-default simulation** — 深灰仪表盘 + 大视频 + 聊天栏 + 参会人列表 + Material 图标 + 蓝主色；头显端一块跟着头走的大浮窗镜像视频。

**Where the plan risked that default, and what I changed** —
- 头显端最接近默认的是 B（镜像大板），被 2D 反例否掉；A 的玻璃**什么都不显示**，只显示笔迹，不挡工作面。
- 专家台去掉聊天、参会人、侧栏；v1 的板带随冻结一起取消，取景框放大。
- 主色从默认蓝改成粉笔黄并定义黄上墨色；红点严格留给"摄像头在共享"。
- "一画即冻结"被用户否决后，没有退回到"按钮冻结"，而是彻底改成实时模型，并把它的代价（笔迹随头走）写进 §1 明示。

**Hard checks**

| Check | Pass? | Evidence |
| --- | --- | --- |
| ≥3 real alternatives with rejection reasons | ✓ | §2 A/B/C（+ 被否决的 A0） |
| Every core task has a 2D counterfactual | ✓ | §1 表 |
| Form/scale follows the spatial justification | ✓ | Full Space 由用户给定；玻璃尺寸由相机内参推出；平时只剩小条 |
| Every window/surface size was internally validated | ✓ | 玻璃 ≈ 55°×33° 在 65°×40° 内；通话条 5×56 + 状态 180 + 间距 = 532 < 616；配对卡 PIN 40 dp 在 1.2 m 可读 |
| Attachments justified vs None/in-place | ✓ | 不用 Toolbar/TabBar/Subwindow；确认在通话条内联；玻璃无材质 |
| Domain-swap test | ✓ | 换成"远程看房"不需要回画、不需要相机内参对齐；换成"远程医疗"要加测量与记录；布局不保留 |
| Every core component has data source + task | ✓ | §3 Components |
| All 16 `ColorScheme` roles explicitly defined | ✓ | §3 tokens 表 16 行 |
| Every standard control/system surface maps to SpatialUI Web | ✓ | §3 map；自定义只剩笔迹/光标/细框 |
| Single primary focus / clear decision | ✓ | 头显：玻璃上的笔迹；专家台：取景框；任务是"指到那个点" |

## 5 · Build — coverage manifest

| # | Kind | From design doc | Implemented in preview.html |
| --- | --- | --- | --- |
| S1 | state | 配对 Lobby | `#hs[data-state="lobby"]` → `#lobby` 可见；`#console[data-state="gate"]` |
| S2 | state | 配对·权限被拒 | `#lobby.perm-denied`（F10 / `jieyan.setPerm(false)`） |
| S3 | state | 直播 Live | `#hs[data-state="live"]`，`#strip` 紧凑，`#glass` 隐形 |
| S4 | state | 直播·我在画 | `#glass.pen`（细框），`#strip-pen[checked]`，专家台 `#vf-workerpen` 芯片 |
| S5 | state | 已暂停共享 | `#hs[data-state="paused"]`，`#vf-paused` |
| S6 | state | 重连中 | `#hs[data-state="reconnecting"]`，`#strip-progress`，`#vf-reconnecting`（F9） |
| T1 | transition | 接入 | `#gate-connect` click-action / Enter，PIN 正确 |
| T2 | transition | 专家画 | `#viewfinder` pointerdown/move/up → 黄笔同时出现在 `#glass-strokes` |
| T3 | transition | 现场人员画 | `#strip-pen` checked-change → `#glass` pointer 拖动 → 蓝笔同时出现在 `#vf-strokes` |
| T4 | transition | 指点 | `#viewfinder` pointermove → `#hud-dot` |
| T5 | transition | 撤销 | `#btn-undo` / Ctrl+Z；`#strip-undo` |
| T6 | transition | 清除 | `#btn-clear` → `#snack` 撤销；`#strip-clear` |
| T7 | transition | 自动淡出 | `#tray-fade` checked-change → 笔迹 8 s 后 2 s 内淡出 |
| T8 | transition | 暂停/恢复共享 | `#strip-share` checked-change |
| T9 | transition | 断线 / 重连 / 超时 | F9 两次；`jieyan.timeoutReconnect()` |
| T10 | transition | 结束会话（确认） | `#strip-end` → 内联 `#strip-confirm`；`#btn-end` → `#dlg-end` |
| T11 | transition | 权限授权 | `#lobby-grant` click-action |
| T12 | transition | 画面不可用 / 过期 | F10（error）、F8（fallback） |
| C1–C7 | component | 通话条、配对卡、视野玻璃、Gate、取景框、粉笔盘、状态签 | `#strip` `#lobby` `#glass` `#gate` `#viewfinder` `#tray` `#status-pill` |
| D1 | binding | 视频帧 normal/fallback/error | 取景框场景 / 灰化 + "画面 3 秒前" / "画面不可用" |
| D2 | binding | 延迟 fps | `#latency` 86 ms·14 fps / — |
| D3 | binding | PIN、地址 | `#lobby-url` `#lobby-pin` |
| D4 | binding | 笔迹（作者/颜色/粗细/淡出） | `polyline` ×2（halo + 色）+ opacity |
| D5 | binding | 对方画笔态 | `#vf-workerpen` |

## 6 · Critique again

**Independent coverage rebuild**（2026-10-19，桌面内置浏览器 1500×900 视口；真实鼠标 + 指针事件 + `window.jieyan.state()` 逐条核对）

- S1–S6：`#hs[data-state]` 与 `#console[data-state]` 同步切换；配对态 `#lobby` 可见、`#strip`/`#glass` 隐藏；画笔开时 `#glass.pen` 出蓝色细框 + "在玻璃上画"芯片，专家台出"对方画笔已开"芯片，通话条状态变"画笔开 · 在玻璃上画"。
- T1：PIN 正确进入直播；错 PIN → `#gate-pin[error]` + 文案；结束会话后门上显示"会话已结束"。
- T2：在 `#viewfinder` 拖一笔 → 黄笔同一时刻出现在 `#glass-strokes` 和 `#vf-strokes`（各 2 条 polyline：外描边 + 色）。
- T3：`#strip-pen` 开后在 `#glass` 拖一笔 → 蓝笔同步到专家台，`#vf-strokes` 里颜色为 `#FFE14D` + `#8CD5FF`。
- T4：悬停取景框 (30%,30%) → `#hud-dot` 落在玻璃的 (30%,30%)，700 ms 后熄灭。
- T5：`#btn-undo` 只撤专家的笔、`#strip-undo` 只撤现场人员的笔。
- T6：`#btn-clear` → 笔迹清零 + `#snack`"已清除全部笔迹 · 撤销"，撤销后回来；`#strip-clear` 直接清。
- T7：`#tray-fade` 开 → 一笔画完 9 s 时 opacity 0.49，10.8 s 后从列表移除。
- T8：共享关 → paused，`#vf-paused` 显示，LIVE 点灭；再开回直播。
- T9：F9/钩子 → reconnecting，`#strip-progress` 与 `#vf-reconnecting` 显示，粉笔盘禁用；恢复回直播。
- T10：`#strip-end` → 内联"结束会话并停止共享？是/否"；`#btn-end` → `#dlg-end`（`:popover-open` 为真）。
- T11：权限被拒 → `#lobby.perm-denied` + [授权] 按钮；授权后恢复。
- T12：fallback → 灰化 + "画面 3 秒前" + "— ms · 0 fps"；error → "画面不可用"遮罩、LIVE 点灭、通话条"画面不可用 · 读回失败"。
- 差异：`sui-alert-dialog` 走浏览器 top layer，截图工具拍不到，但 DOM 状态确认已打开。

**Quality bar**

| Criterion | Met? | Evidence |
| --- | --- | --- |
| App's job is a clear decision/task outcome | ✓ | "指到那个点"：画即同步，光标即指点 |
| Form/scale follows the spatial justification | ✓ | 透明玻璃 + 小条，无常驻大窗；玻璃尺寸由相机内参推出 |
| Spatialization justified affordance-by-affordance | ✓ | §1 表，每行有 2D 反例；头锁定的代价明示 |
| ≥3 alternatives compared, rejects have reasons | ✓ | §2（A/B/C + 被否决的 A0） |
| Every window/surface records final default/min/max | ✓ | §3 sizing 表 |
| Design derives from domain | ✓ | 粉笔/石板/直播红点、相机视场玻璃、双色笔 |
| SpatialUI bundle inline; mapped tags registered, present, event-wired | ✓ | 页面内无未注册 `sui-*`；事件走 `click-action`/`checked-change`/`select`/`value-change`/`dismiss-request`/`snackbar-result` |
| No unjustified native duplicate of a `sui-*` control | ✓ | 原生元素只剩 `<svg>` 笔迹、光标、细框 |
| Root surface uses vibrant + Material.Regular glass | ✓ | 通话条/配对卡/粉笔盘用 `--Background-MaterialRegular` + 库同款 blur |
| No design/debug-only controls in product UI | ✓ | 评审钩子只在键盘 F8/F9/F10 与 `window.jieyan` |
| preview.html is a triggerable state machine, full coverage | ✓ | 上文逐条 |
| Signature present, surroundings quiet | ✓ | 玻璃平时隐形，只有笔迹和一个点；通话条一行 |

**Patch log**

| Round | Problem | Target | Expected improvement | Re-ran Build+critique? |
| --- | --- | --- | --- | --- |
| v2-1 | 合成指针事件下 `setPointerCapture` 抛错会跳过起笔 | 两处 capture 加 try/catch | 真实与合成输入都稳定起笔 | ✓ |

**Device validation** — `not_performed`（Web 只验证逻辑与布局；真机的舒适度、头锁定手感、玻璃与相机内参的对齐误差、VST 读回帧率/延迟均未验证）。

**Verdict** — **pass**（设计包可进入 `spatial-design-to-app`）。进入实现前需用户确认：① 应用名「借眼 Jieyan」与包名 `io.embodit.jieyan`；② v1 不做音频、不做公网中继、笔迹跟视野走不贴实物；③ 模拟器用合成相机源验证同步回路，VST 真画面与玻璃对齐只能在 Swan 真机验证。
