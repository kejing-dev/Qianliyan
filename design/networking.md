# 借眼 Jieyan · 网络与视频链路方案（feasibility）

> 版本 v2 · 2026-10-19。配合 `design-doc.md`（产品设计）和 `ux-flow.html`（UI/UX 流程图）。只讲链路：怎么把 Swan 眼前的画面送到协助者的浏览器、怎么把笔迹送回来、能不能做、代价多大。

## 0 · 结论先行

| 方案 | 适用 | 可行性 | 关键依据 | 剩余不确定 |
| --- | --- | --- | --- | --- |
| **A · 局域网直连**（头显自带 HTTP + WebSocket 服务，MJPEG 帧） | v1 演示、同一 Wi-Fi 的现场/办公室场景 | **高** | 相机读回路径在 6.1 文档里是正式 API（SpatialML Readback Mode）；官方 SuperResolution 样例的主 pipeline 以 10 Hz 连续跑并把 RGBA 读回应用；Android 上内嵌 Ktor/WebSocket 是成熟做法；协助者端只是浏览器 | 读回能否稳到 15 fps、端到端延迟多少——**真机测一天就有数** |
| **B · 互联网 WebRTC**（头显 libwebrtc + 浏览器原生 RTCPeerConnection，信令 + STUN/TURN） | C 端：家人/朋友在任何地方用手机或电脑帮忙 | **高-中** | libwebrtc 的 Android 构建不依赖 Google 服务，PICO（无 GMS 的 Android 16）可用；自定义 `VideoCapturer` 可以把读回的帧喂进去；浏览器端零安装 | 需要一个很小的信令服务器 + TURN（或直接用托管 SFU，如 LiveKit）；多 1.5–2 周工作量 |
| **C · 公网中继 WebSocket + 自适应降帧**（A 的协议不变，两端都主动连一台云主机） | **跨网第一步**：头显在工厂（热点/厂区 Wi-Fi），PC 在远处办公室 | **高**（画质中） | 只走出站 443，穿 NAT/防火墙/客户端隔离；中继几十行代码；加"确认滞后就降 fps/画质"的背压后，4 Mbps 上行可用 | 弱网体验不如 WebRTC（无 FEC/重传），码率 3–6 Mbps 偏高 |
| **D · 托管 RTC**（火山引擎 veRTC / 声网 / 腾讯 TRTC：Android 外部视频采集 + Web SDK） | C 端与跨网正式版，国内工厂网络 | **高** | 信令、TURN、弱网、录制全包；PICO 无 GMS 不影响；veRTC 与 PICO 同门 | 按分钟计费、多一个第三方 SDK；约 1 周接入 |

**建议：v1 做 A（开发与模拟器验证必须有），协议从第一天就按归一化坐标、序号、状态重放设计，传输层做成可替换；v1 跑通后 2–3 天加 C 就能在工厂跨网演示；画质/弱网成为问题时换 D 或 B。** 同一 Wi-Fi 在工厂几乎碰不到（PC 远程、厂区 Wi-Fi 常有客户端隔离），所以 A 是验证形态，不是交付形态。语音不做：以后在 B/D 上加一条音频轨道即可，企业版自行加。

## 1 · 目标与约束

- 单向视频：头显 → 协助者；双向数据：笔迹、光标、画笔开关、共享开关、清除/撤销、状态。
- 目标延迟：局域网视频 ≤ 250 ms、笔迹 ≤ 80 ms；公网视频 ≤ 500 ms。
- 协助者零安装：浏览器（电脑 / 手机）。
- 头显侧约束（来自 6.1 文档）：
  - 应用能拿到相机像素的**唯一正式路径**是 SpatialML Readback Mode：`rectifiedVSTAccess` 把右眼（或左眼）校正后的 RGB 图写进 pipeline → `convertColor` 到 RGBA 全局 dynamic texture → 应用 `readbackContentSuspend()` 拿到 `SharedMemory` 里的 RGBA 字节。需要 `android.permission.CAMERA`。
  - Secure Mode 不能用：它的像素永远不出 runtime，没法上网。
  - **模拟器没有相机**：`rectifiedVSTAccess` 在模拟器不出真实数据。所以链路要做"相机源"抽象：真机 = SpatialML 读回；模拟器 = 合成源（循环图序列 + 时间戳水印）。网络、笔迹、状态机全部可以在模拟器验证。
  - 文档明确：给张量赋值/读回"需要应用与 SpatialML 框架之间的数据传输，可能耗时"，要在协程里异步调；pipeline 构建一次、反复 `submit`，不要每帧重建。
  - Android Camera2 取 VST 的方式**6.1 文档没有**，标为未确认；如果真机上可用，视频管线更简单（`ImageReader`/`Surface` 直喂编码器），其余不变。
- 不做：语音、多协助者、笔迹贴实物（深度 + 锚点）。

## 2 · 视频采集（头显）

```
VST 相机 ──SpatialML pipeline（build once, submit @ N Hz）──▶ 全局 RGBA dynamic texture
   rectifiedVSTAccess(right, cameraMatrix, timestamp) → convertColor RGB→RGBA → 全局张量
                                     │
                     readbackContentSuspend()（协程，IO 线程）
                                     ▼
                         RGBA 字节（W×H×4，SharedMemory，用完即 close）
                                     ▼
                      编码线程：JPEG（v1）或 I420→H.264（v2）
```

- 分辨率：会话创建时定死（`InitInfo.imageWidth/Height`），一个会话内不能改。样例用 512×512；也见过 580×326。**真机第一件事：问清楚 Swan 的 VST 原生比例**，按 16:9（例如 640×360）或 4:3 定；协助者端取景框按实际比例显示。
- 节奏：样例主 pipeline 10 Hz。我们目标 15 fps：每 66 ms submit 一次 + 读回一次。读回是一次 W×H×4 的共享内存拷贝（640×360 ≈ 0.9 MB），估计 2–5 ms；JPEG 编码（CPU，质量 70）估计 8–15 ms。都在工作线程，主线程不碰。
- 背压：编码跟不上就丢帧（只保留最新一帧），永远不排队——远程协助要的是"现在"，不是完整录像。
- 相机内参：同一个 `rectifiedVSTAccess` 顺手把 `cameraMatrixResult`（3×3，fx fy cx cy）读出来一次，用于把头显上的"视野玻璃"做成和相机视场一样大（1.0 m 处宽 = W/fx、高 = H/fy），这样协助者在画面上画的归一化坐标回到头显就是同一条视线。右眼相机和头中心有几厘米偏置，1 m 处视差 ≤ 2°，可接受。
- 隐私：共享时通话条红点常亮；"暂停共享"= 停止 submit + 停止读回，不只是停发包。

## 3 · 编码：MJPEG（v1）还是 H.264（v2）

| | MJPEG over WebSocket | H.264 over WebRTC |
| --- | --- | --- |
| 头显侧 | `Bitmap.compress(JPEG)` 或 libjpeg-turbo，CPU，无状态 | `MediaCodec` 硬编（骁龙），需要 RGBA→I420 转换（libyuv，640×360 < 2 ms） |
| 协助者侧 | `<img>`/`<canvas>` 画 Blob，任何浏览器 | 浏览器原生解码，任何浏览器 |
| 640×360@15fps 码率 | 每帧 30–50 KB → **3.5–6 Mbps** | **0.5–1 Mbps** |
| 延迟 | 无 GOP，每帧独立，局域网最低 | 编码器 + 抖动缓冲，多 50–150 ms |
| 弱网 | 没有拥塞控制，丢包就卡 | 自带拥塞控制、FEC、重传 |
| 实现量 | 1–2 天 | 1–1.5 周（含自定义 capturer） |

v1 局域网用 MJPEG：Wi-Fi 5 GHz 下 6 Mbps 不是问题，延迟最低，代码最少。C 端公网必须 H.264 + WebRTC。

## 4 · 传输

### 方案 A · 局域网直连（v1）

```
Swan                                            协助者浏览器（电脑 / 手机）
┌──────────────────────────────┐                ┌──────────────────────────┐
│ Ktor 内嵌服务（CIO）:8765      │  GET /         │ 协助台页面（assets 里的    │
│  GET /  → 页面（HTML/JS）     │◀───────────────│   单文件 HTML + JS）       │
│  WS /ws → 二进制帧 + JSON     │◀══ WebSocket ═▶│ <canvas> 画帧、画笔迹      │
│ 会话状态机（权威）、PIN 门      │                │                          │
└──────────────────────────────┘                └──────────────────────────┘
```

- 头显是服务器：不需要任何外部基础设施，断网（没有外网）也能用。
- 发现：配对卡显示 `http://<头显 IP>:8765`（`ACCESS_WIFI_STATE`/`ACCESS_NETWORK_STATE` 读 IP）。可选 mDNS 广播 `jieyan.local`（Android `NsdManager`）——电脑/iPhone 浏览器一般能解析 `.local`，**Android 手机浏览器不一定**，所以 IP 永远显示。
- 安全：4 位 PIN 一次会话一换，连错 5 次锁 60 s；局域网内走明文 HTTP/WS（自签证书会让浏览器弹警告，C 端体验更差；局域网可接受）。
- 一次只允许一个协助者（第二个连进来提示"已有协助者"），v1 不做多人。
- 浏览器兼容：WebSocket 二进制 + `createImageBitmap` 解 JPEG，Chrome/Edge/Safari/Firefox 和手机浏览器都行。

### 方案 B · 互联网 WebRTC（C 端）

```
Swan ── libwebrtc（Android，无 GMS 依赖）──┐           ┌── 浏览器 RTCPeerConnection
   自定义 VideoCapturer ← 读回帧（I420）      │ P2P（DTLS-SRTP）│   <video> 显示
   H.264 硬编（libwebrtc 的 MediaCodec 路径）  │◀══════════════▶│   DataChannel 收发笔迹
   DataChannel：笔迹/光标/状态               │           └──
                 │                          │
                 ▼ 信令（WSS，按 6 位码进房）  ▼
          小信令服务（Node/Go，几十行）+ STUN（公共）+ TURN（coturn 自建，或 Cloudflare/Twilio TURN）
```

- 配对体验不变：头显配对卡显示 `jieyan.app/j` + 6 位码；协助者在手机/电脑浏览器输码。
- 依赖：`io.getstream:stream-webrtc-android`（libwebrtc 预编译，纯 NDK，不要 Google Play 服务）；浏览器端零依赖。
- 自定义采集：实现 `VideoCapturer`，把读回的 RGBA 转 I420 后 `CapturerObserver.onFrameCaptured(VideoFrame)`，这是 libwebrtc 的公开扩展点。
- 更省事的替代：**LiveKit**（开源 SFU，有托管版免费额度）——Android SDK（无 GMS）+ JS SDK，TURN/录制/多人都带；自定义视频源同样支持。多一个第三方，但把信令、TURN、弱网都包了。
- 语音：以后加 `AudioTrack` 即可（头显麦克风 + 协助者麦克风），不动其它。
- 代价：一个常驻的小服务（VPS 每月几十块）；TURN 流量按用量；证书（WSS/HTTPS 必须，否则浏览器不给 WebRTC）。

### 方案 C · 公网中继 WebSocket + 自适应降帧（跨网第一步）

```
Swan（工厂：手机热点 / 厂区 Wi-Fi） ──出站 WSS 443──▶ 中继（云主机，按 6 位码配对房间）◀──出站 WSS 443── PC 浏览器（办公室）
```

- 协议与 A 完全一样，只是两端都"向外连"，中继只做转发（Node/Go 几十行，带 6 位码房间、空闲超时、单协助者限制）。
- 背压：PC 端每收到一帧回一个 `ack{seq}`；头显看到未确认帧 > 2 就跳过发送（只发最新帧），连续滞后 2 s 就把质量从 70 降到 50、fps 从 15 降到 8；恢复后逐步升回。这样工厂上行 4 Mbps 也不会越积越卡。
- 安全：WSS + 6 位码 + 中继不落盘；中继看得到明文帧（没有端到端加密），企业版若在意再上 B/D。
- 局限：没有 FEC/重传，弱网（手机 4G 抖动）会掉帧；码率比 H.264 高 5–8 倍。它是"两三天能用上"的方案，不是终态。
- 配对体验不变：配对卡显示公网短址（如 `jieyan.app/j`）+ 6 位码；PC 打开短址输码。

### 方案 D · 托管 RTC（veRTC / 声网 / TRTC）

- 头显：SDK 的"外部视频采集"接口（推自定义帧，I420/RGBA）接读回的帧；Web：官方 JS SDK 收流；房间 token 由一个小服务签发。
- 它们都在国内有节点，工厂网络友好；弱网、TURN、录制、后续语音都自带。
- 费用按分钟计；C 端规模化时比自建 TURN 省心。

## 5 · 消息协议（A/B 共用，传输层可换）

所有坐标是**相机画面的归一化 0–1**；所有消息带递增 `seq`；头显是状态权威，协助者端只镜像。

| 方向 | 消息 | 内容 |
| --- | --- | --- |
| 协助者→头显 | `hello` | `{pin, client:"web", ua}` → 回 `welcome{session, imageW, imageH, fps}` 或 `reject{reason}` |
| 头显→协助者 | `frame` | 二进制：`[u32 seq][u64 tsMs][JPEG bytes]`（B 方案下是视频轨道，不走消息） |
| 双向 | `stroke.begin / point / end` | `{id, owner:"expert"|"worker", color, width, pts:[[x,y],…]}`，point 每 ≤33 ms 批量发一次 |
| 双向 | `undo{id}` `clear{}` | 撤自己的笔 / 清全部 |
| 协助者→头显 | `pointer{x,y}` | ≤20 Hz，头显 700 ms 无更新即隐藏黄点 |
| 头显→协助者 | `state` | `{share, pen, data:"normal"|"stale"|"error", strokes:[…]}`——连接/重连时全量，之后增量 |
| 协助者→头显 | `fade{on}` | 8 秒淡出开关（头显也按同一规则淡，两边一致） |
| 双向 | `end` | 结束会话；头显换 PIN |
| 双向 | `ping/pong` | 2 s 一次，算 RTT 显示在协助台 |

重连语义：WebSocket 断开后协助者端每 2 s 重连，带上原 `session`；头显 30 s 内保留状态（笔迹、画笔态），重连成功发一次全量 `state`；超 30 s 两边回到配对。

## 6 · 延迟与带宽预算（640×360，15 fps，估算，真机校准）

| 环节 | 局域网 MJPEG（A） | 公网 WebRTC（B） |
| --- | --- | --- |
| VST 采集到 pipeline 出帧 | 30–60 ms | 30–60 ms |
| 读回（共享内存拷贝） | 2–5 ms | 2–5 ms |
| 编码 | JPEG 8–15 ms | H.264 硬编 15–30 ms |
| 网络 | 2–10 ms | 30–120 ms |
| 解码 + 显示 | 5–15 ms | 抖动缓冲 + 解码 50–150 ms |
| **视频端到端** | **≈ 60–120 ms**（目标 ≤ 250） | **≈ 150–400 ms**（目标 ≤ 500） |
| 笔迹/光标 | 10–40 ms | 40–150 ms |
| 上行带宽 | 3.5–6 Mbps | 0.5–1 Mbps |

头显功耗：读回 + JPEG + Wi-Fi 发送估计多 15–25% 电量消耗，一次协助 30 分钟内没问题；真机量。

## 7 · 平台与权限清单

- `CAMERA`（SpatialML 读回必需；运行时申请，拒绝后仍可配对但"画面不可用"）、`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`（显示 IP）；B 方案不需要额外权限（语音时再加 `RECORD_AUDIO`）。
- 生命周期：应用退后台或容器关闭 → 停 pipeline、关读回、关 socket、停服务器；回前台重新起。Stage 应用切到系统面板时同样处理（协助者端看到"对方已暂停"）。
- 网络类型：建议 5 GHz Wi-Fi；头显和协助者在同一网段（企业 Wi-Fi 的客户端隔离会挡住 A，这是 B 存在的另一个理由）。

## 8 · 风险与第一周验证计划

| 风险 | 影响 | 验证（Swan 真机，1–2 天） |
| --- | --- | --- |
| 读回达不到 15 fps 或延迟高 | 画面卡、指点错位 | 写一个最小 app：512×512 与 640×360 各跑 60 s，记录 submit→readback 完成的间隔与耗时；目标 ≥ 12 fps、单帧 < 40 ms |
| VST 原生比例/视场未知 | 取景框和玻璃比例错 | 读 `cameraMatrixResult` + 实际图像尺寸，对着一个已知尺寸的物体核对视场 |
| 头锁定面板 API | 玻璃不跟头 | 在模拟器验证：每帧按 HMD 位姿更新面板实体 transform；或系统头锁定附着（下游用 pico-dev-knowledge 查） |
| 端到端延迟 | 体验 | 头显画面里放一个毫秒时钟，协助台截图比对 |
| 手机浏览器 | C 端 | iPhone Safari / Android Chrome 各试一次：WebSocket 二进制、`createImageBitmap`、触摸画笔 |
| Camera2 是否可用 | 可能更简单 | `CameraManager.getCameraIdList()` 看一眼，有就试 `ImageReader`；没有就按读回走，不纠结 |
| 企业 Wi-Fi 隔离 | A 连不上 | 用手机热点兜底；长期靠 B |

## 9 · 工作量（单人）

- v1（A）：头显 Stage + SpatialUI 面板 + 读回/JPEG/Ktor + 协助台页面（电脑 + 手机） + 模拟器合成源 + 真机校准：**2–3 周**。
- B（WebRTC + 信令 + TURN，或 LiveKit）：**+1.5–2 周**；语音在 B 上 **+2–3 天**。
