# 千里眼 · 设计包 v3 变化处：真实画面（SpatialML 读回）+ 双向语音

> 2026-10-21 · 设计站（回设计站 #1，`changedOnly`）。只写**变化处**；没写到的部分（视野玻璃、笔迹、指点、撤销 / 清除、淡出、暂停、重连、结束确认、配色字体、容器 STAGE_MIXED / 骨架 D）一律照 `design-doc.md` v2 不变。
> 来源：用户 2026-10-21 在真 Swan（PB324XJGL2090081G）上试 v0.1.3 后提出两件事：①专家网页要看到头显**真实画面**（用 SpatialML），②**双向语音**。同时实测到公司 Wi-Fi 隔离（电脑 10.73.x、头显 30.16.x 互不通），USB `adb forward` 后 `http://localhost:8765` 可用。
> 目标应用版本：v0.2.0。

## 0 · 变化处清单（门 B 只看这些）

| # | 变化 | 影响的状态 / 面板 | 真机依赖 |
| --- | --- | --- | --- |
| C1 | 真实画面：`VstReadbackCameraSource` 用 SpatialML Readback Mode 实现，替换 SYNTH；真机上**永远不再回落到合成画面** | 取景框、S3 | **是**（模拟器无 VST） |
| C2 | 首帧加载态：专家连上后先看到「正在打开头显摄像头…」，5 s 内出第一帧 | 新 S10 | 是 |
| C3 | 画面不可用改版：分原因说人话 + 专家可点 [重试]；不再显示合成画面 | S8 | 是 |
| C4 | 帧率 / 延迟指示：状态签显示真实 fps（按 VST 时间戳去重）与约略延迟；卡顿（< 8 fps 持续 3 s）单独一态 | 状态签、新 S11、S7 | 是 |
| C5 | 模拟器演示画面：合成源只在模拟器用，取景框角上常显「演示画面 · 模拟器没有摄像头」 | 新 S3d | 否 |
| C6 | 双向语音：专家连上即自动通话；头显 `AudioRecord(VOICE_COMMUNICATION)` + AEC，浏览器 `getUserMedia`；音频走同一台头显的第二条 WebSocket `/audio`，16 kHz PCM，不上云 | S3、通话条、专家台顶栏 | AEC / 回声 / 延迟是 |
| C7 | 静音：两边各一个静音键；对方看得到「已静音」 | 新 S12 / S13 | 否 |
| C8 | 说话指示：谁在说话就亮三格声波；自己的麦克风键带音量环（知道麦克风在工作） | 新 S14 | 否（模拟器用调试注入） |
| C9 | 麦克风权限：头显启动时与摄像头一起申请；拒绝 = 只能看画面不能说话（不挡配对） | S1 权限行、新 S2m | 否 |
| C10 | 浏览器麦克风规则：只有 https 或 localhost 能开麦。两条路：同一网络 https 自签证书（第一次「仍要继续」；Wi-Fi 隔离就一起连手机热点）和 USB `localhost`（今天就能用，专家电脑得在旁边） | 新 E4 / E5 / E6 / E7 | https 证书在 Android 上生成是**未确认** |
| C11 | 配对卡改版：一个地址 + PIN + 两行权限 + 「连不上？① 手机热点 ② USB 线」说明 | S1、新 S1u、S2s、配对卡尺寸 | 否 |
| C12 | 通话条加第 6 个键（🎙 静音，最左）；密度上限 5 → 6；宽 616 → 680 dp | 通话条 | 否 |
| C13 | 暂停共享只停画面不停通话（「已暂停画面 · 麦克风开着」）；重连时通话一起断一起回；只断语音时单独一态；结束会话挂断；接通 / 挂断各一声 0.3 s 提示音 | S5、S6、新 S6a、S9 | 否 |
| C14 | 能不能说话两边对称：专家开不了麦时头显也显示「专家只能听，不能说」 | 新 S15 | 否 |

评审（2026-10-21，子 agent 一轮，12 条）已采纳：USB 改为兜底、加手机热点；`/audio` 单断态 S6a；两边对称 S15；暂停时提示麦克风开着 + 接通提示音；`setCommunicationDevice` 选扬声器；回声兜底只开专家一侧；按文档改成 RGB → `convertColor` → RGBA；比例探测挪到配对阶段；刷新后「点一下恢复声音」E7；声波不改文字；公司浏览器策略分支；补 QA 组合截图。

不变（明确不做）：不冻结画面（用户否决过）、不加弹窗、不加铃声 / 接听步骤、不做云中继、不做多专家、不做录音录像。

## 1 · 真实画面（C1–C5）

### 1.1 依据（全部来自 SDK 6.1 文档 + pico-dev-knowledge）

- 应用能拿到 VST 像素的正式路径是 SpatialML **Readback Mode**：`rectifiedVSTAccess` 写入全局张量 → `readbackContentSuspend()` 读回应用；需要 `android.permission.CAMERA`，否则 SpatialML 拒绝读回（`spatial-sdk_spatialml_spatialml-privacy-statement.md`）。Secure Mode 的像素出不了 runtime，不能上网，不用。
- 依赖：项目 BOM `com.pico.spatial:bom:6.1.9` 已含 `com.pico.spatial.ml:securemr` 与 `com.pico.spatial.ml:readback`（已核对 Volcengine 仓库的 BOM pom）。清单要加 `<meta-data android:name="pico.spatial.use_experimental_api" android:value="1"/>`（`spatial-ml_getting-started_prerequisites.md`）。
- `rectifiedVSTAccess(rightImageResult: Tensor?, leftImageResult: Tensor?, timestampResult: Tensor?, cameraMatrixResult: Tensor?)`：API 参考说图像结果是 `DataType.Image.R8G8B8_U` 或 `R8G8B8A8_U`，算子卡只写了 3 通道 RGB（两份文档不一致）；尺寸建议等于 `InitInfo.imageWidth/Height`；`timestampResult` 用 `Tensor.TimeStampInitInfo()`；`cameraMatrixResult` 3×3 `FLOAT32`（`com.pico.spatial.ml.securemr.md`、`spatial-ml_reference_operators_rectifiedvstaccess.md`）。
- 文档推荐路径：「Convert a three-channel RGB result into it with `RGB_TO_RGBA` before binding or readback」（`spatial-ml_reference_tensor-types-and-enums.md`）——所以按两份文档都认的 3 通道 RGB 取帧，再 `convertColor` 成 RGBA。
- 只能读回 `GlobalTensor`（`session.newGlobalTensor`），本地张量不行；`TensorContent` 是 `AutoCloseable`，`use {}` 里拷完就关（`spatial-ml_workflows_read-data-back-to-the-app.md`）。
- Readback Mode 的会话不要 SpatialML 容器：`InitInfo(..., containerWidth = 0, containerHeight = 0, containerDepth = 0)` 或 `containerType = DISABLED`（`spatial-ml_reference_core-api.md`）。千里眼是 Full Space Stage，SpatialML 容器在 Full Space 下本来就会被隐藏，正好不需要。
- 图建一次、反复 `submit`；协程里跑；`submit` 失败容忍 5 次连败（`spatial-ml_workflows_async-pipeline-patterns.md`）。
- 模拟器：`rectifiedVSTAccess` 不出真实数据（`spatial-ml_getting-started_prerequisites.md` §Running on the emulator）。

**Pipeline Zoo 核对**（`pico-cli spatialml pipeline search`，2026-10-21）：camera / vst / readback / image 均 0 条；只有 `picoxr/face-mediapipe-pipeline`（spatial）与 `picoxr/xr-face-mediapipe-pipeline`（xr），是人脸检测。它能复用的只有「取帧」这一步，而这一步在我们的图里就是一个算子；搬它要拆掉模型、NMS 和显示管线，比直接写更费事。**结论：不套 Zoo 包，代码里建一条 1 算子的最小图。**
（注：`spatialml` 技能说「只取 VST、不跑模型」本不属于它的范围；但 SpatialML Readback 是 6.1 文档里应用拿 VST 像素的唯一正式路径，所以照用户要求用它，技能里关于图构建、读回、隐私、清理的规则照守。）

### 1.2 图与张量（给研发站的契约）

```
acquire + convert（每次 submit）                 readback（应用侧，IO 协程）              encode（工作线程）
rectifiedVSTAccess(                              frame.readbackContentSuspend().use {     RGBA → Bitmap(ARGB_8888)
  rightImageResult  = rgb（本地 R8G8B8_U）          copyPixelsFromBuffer               ──▶ compress(JPEG, q)
  leftImageResult   = null                        }                                       → 现有 MJPEG 通道
  timestampResult   = stamp   ──────────────────▶ stamp 读回：和上次相同 = 不是新帧，跳过   [u32 seq][u64 ts][jpeg]
  cameraMatrixResult= camK    ── 读一次并缓存 ──▶ fx fy cx cy → 视野玻璃尺寸（原有逻辑）
)
convertColor(RGB_TO_RGBA, rgb, frame)  ─────────▶（frame 是全局，可读回）
```

| 张量 | 类型 / 形状 | 局部 / 全局 | 生命周期 |
| --- | --- | --- | --- |
| `rgb` | `Image.R8G8B8_U`，`(H, W)`；H×W 见 1.3 | 本地（图内中转） | 随图 |
| `frame` | `Image.R8G8B8A8_U`，`(H, W)` | 全局（要读回） | 会话内一份 |
| `stamp` | `TimeStampInitInfo()`（4 × INT32） | 全局（要读回） | 会话内一份 |
| `camK` | `FLOAT32 (3, 3)` | 全局（读一次） | 会话内一份 |

- 读回成 RGBA：正好是 Android `ARGB_8888` 的内存字节序，`copyPixelsFromBuffer` 一步进 Bitmap，免逐像素循环。
- **未确认**：`convertColor` 填的 alpha 值。若不是 255，JPEG 可能发黑——编码前把 Bitmap 设为不透明（`setHasAlpha(false)`）兜底；spike 截一帧核对。若非 dynamic 的 `R8G8B8A8_U` 读回不了，换成官方样例的 `R8G8B8A8_U_DYNAMIC`（同一条路径）。
- 读回与 `submit` 完成的先后同步方式文档没写死（**未确认**）：用 `stamp` 去重解决——读回到的时间戳没变就当作没有新帧，不发、不计 fps。这也让 fps 显示是真的。
- 节奏：`runContinuously` 式循环，目标 15 Hz（每 66 ms 一次 submit + 一次读回）；编码跟不上就丢，永远只留最新一帧（原有 conflated channel 不变）。
- 暂停共享（◉ 关）= 停 submit + 停读回，不只是停发包（原规则不变）。
- 清理顺序：取消协程 → 关所有未关的 `TensorContent` → 释放 pipeline → 全局张量 → session → instance（父在子后）。退后台、关 Stage、结束会话、切 S8 都走这条。

### 1.3 分辨率与比例（真机第一步定）

- 默认请求 **960×540**（16:9）；读到的 `camK` 若 `|fx − fy| / fx > 3%`（像素不是方的 = 比例不对），换 **1024×768**（4:3）再试；仍不行用 **512×512**（官方样例尺寸）。取景框按 `(W/fx) : (H/fy)` 显示真实视场比例，不硬套 16:9。
- 这个比例探测**在配对阶段做一次**（相机授权后、专家还没连上时），结果缓存在应用里，以后的会话直接用；所以专家连上后不会因为换分辨率重建而超时。S10 的 5 秒超时按「每一次建图」单独计时。
- 降级：连续 3 s 实测 < 12 fps → JPEG 质量 70 → 55；再 3 s 仍 < 10 fps → 下次会话改用 640×360（会话内分辨率不能改，`InitInfo` 定死）。
- 带宽：960×540 q70 约 60–90 KB/帧 × 15 = 7–11 Mbps；USB `adb forward` 和 5 GHz Wi-Fi 都够；不够时上面的降级会把它压到 4 Mbps 以下。

### 1.4 用户看到什么（状态）

| 态 | 何时 | 专家台 | 头显通话条 |
| --- | --- | --- | --- |
| **S10 首帧加载中**（新） | 专家刚连上 / 共享恢复 / [重试]，还没收到第一张真实帧 | 取景框深石板底 + 环形进度 + 「正在打开头显摄像头…」+ 副文案「通常 1–3 秒」；笔迹盘可用（可以先画） | 「正在打开摄像头…」labelSecondary |
| S3 直播（改） | 有真实帧 | 真实画面；状态签 `● 直播  15 fps · 约 120 ms · USB` | 不变（不给现场人员看 fps） |
| **S11 画面卡顿**（新） | 实测 < 8 fps 持续 3 s（但还有帧） | 状态签变 alert 色 `卡顿 6 fps · 约 400 ms`；取景框右上芯片「画面有点卡 · 已自动降画质」；不遮挡画面 | 不变 |
| S7 画面过期（文案不变） | 连续 3 s 没有新帧 | 灰化 + 「⏱ 画面 3 秒前」 | 「画面 3 秒前」 |
| S8 画面不可用（改） | 5 s 内无首帧 / pipeline 连败 5 次 / 权限缺 / 设备无 VST | 遮罩 ⚠ + 一行原因（见下）+ [重试]（粉笔黄）；**不显示任何合成画面**；笔迹、语音照常 | 「画面不可用」error 色 + 原因短语 |
| **S3d 演示画面**（新，仅模拟器） | `CameraSources.onEmulator` | 合成画面 + 左下常显芯片「演示画面 · 模拟器没有摄像头」（alert 描边） | 不变 |

S8 原因文案（专家台一行 / 头显短语）：
- 权限：「头显没有摄像头权限 · 请现场人员在配对卡上点 [授权]」/「没有摄像头权限」
- 首帧超时：「头显 5 秒内没给出画面 · 点 [重试]」/「摄像头没响应」
- 读回失败：「读取头显摄像头失败 · 点 [重试]，还不行请重启千里眼」/「读回失败」
- 设备无 VST：「这台设备没有透视摄像头」/「设备无摄像头」

[重试] → 消息 `video.retry` → 头显按 1.2 的清理顺序拆掉重建 → S10。每次会话 [重试] 不限次，但两次之间至少 3 s（按钮倒计时）。

**合成画面的规矩（C5）**：`SyntheticCameraSource` 只在 `onEmulator == true` 时创建；真机上 VST 不可用时进 S8，不是 SYNTH。调试广播可以在模拟器上强制 S10 / S11 / S8 各原因，供 QA 截图。

### 1.5 延迟怎么算

`约 X ms` = 浏览器显示时刻 − 帧的读回时刻（头显 `elapsedRealtime`，经 ping/pong 估出的时钟差换算到浏览器时钟）。不含 VST 曝光到读回这一段（文档没给时间戳格式，**未确认**），所以标「约」。研发站真机用「头显视野里放手机秒表、专家台截图对比」校一次，把常数补进去。

## 2 · 双向语音（C6–C13）

### 2.1 链路

```
头显                                                              专家浏览器
AudioRecord(VOICE_COMMUNICATION, 16 kHz, mono, PCM16)              getUserMedia({audio:{echoCancellation, noiseSuppression, autoGainControl}})
  + AcousticEchoCanceler / NoiseSuppressor（isAvailable 才开）        → AudioWorklet 切 20 ms → Int16 16 kHz
  → 20 ms 一包 ─────── WS /audio（二进制，与 /ws 分开）───────────▶ 抖动缓冲 60 ms（上限 200 ms，超了丢旧的）→ AudioContext 播放
AudioTrack(USAGE_VOICE_COMMUNICATION) ◀── 抖动缓冲 60 ms ◀──────── 20 ms 一包
AudioManager.mode = MODE_IN_COMMUNICATION（通话期间；挂断恢复）
```

- 为什么单开 `/audio`：画面帧 60–90 KB 一张，和 640 字节的语音包挤一条 TCP 会被大帧堵住（队头阻塞）；分两条连接，语音不被画面拖慢。PIN 与 session 校验同 `/ws`，`/audio` 握手带 `session`。
- 包格式：`[u16 seq][u8 flags][PCM16 × 320]`，`flags` bit0 = 发送方静音（静音时每 200 ms 发一个空包维持状态，不发声音数据）。
- 为什么 PCM 不 Opus：16 kHz 单声道 256 kbps，相对画面 4–11 Mbps 可以忽略；PCM 两边零依赖、零编码延迟。Opus（Android `MediaCodec audio/opus` + 浏览器 WebCodecs）留到跨网（公网中继）时再换，传输层已可替换。
- 目标：嘴到耳 ≤ 300 ms（局域网 / USB）。
- 不用 SpatialML 的 `captureMicrophone` / `outputSounds`：那是给模型喂音频的，没有通话回声消除；`AudioRecord` + AEC 是 Android 通话的标准做法。
- 外放：通话模式下 Android 默认可能走「听筒」，Swan 上走向**未确认** → 用 `AudioManager.setCommunicationDevice()` 明确选头显扬声器（API 31+，项目 minSdk 35），列入 spike 第 ① 步。
- 回声：头显侧靠 AEC（同一 audio session 的 `AudioTrack` 作参考），`AcousticEchoCanceler.isAvailable()` 在 PICO 上**未确认**。专家侧浏览器自带 AEC 对网页 AudioContext 播放的声音是否生效**未确认**。兜底**只开在专家一侧**，避免两边互相压：现场声音在播时，专家麦克风暂停发送（半双工，专家松口 300 ms 后恢复）。专家台顶栏下常驻一行「戴耳机通话效果最好」（每次会话出现，可点「知道了」收起）。
- 接通 / 挂断提示音：两端各播一声 0.3 秒的短音（不是铃声，不需要接听），让旁边的人也知道开始 / 结束通话了。
- 权限：头显 `RECORD_AUDIO`（新增），启动时和 `CAMERA` 一起由 `PermissionActivity` 申请；浏览器麦克风在专家点 [连接] 那一下（用户手势）申请，同一下解锁 AudioContext 播放。
- 专家刷新页面：页面会凭 session 自动重连，但没有用户手势就不能恢复播放 → 取景框左下挂芯片「🔇 点一下恢复声音」（E7），点一下即恢复；不是弹窗，不挡画面。
- 只断一半：`/audio` 和 `/ws` 可能单独断。`/audio` 断了而画面还在 → S6a「语音断开 · 正在重连」，`/audio` 每 2 s 自己重试，🎙 键禁用；`/ws` 断了按原 S6 处理（两条一起）。

### 2.2 浏览器只在 https / localhost 给麦克风（C10）

| 路 | 专家怎么打开 | 麦克风 | 一次性麻烦 | 何时用 |
| --- | --- | --- | --- | --- |
| **B · 同一网络 https（主路）** | 浏览器输入配对卡上的 `<ip>:8765` → 我们的提示页 E6 → [继续] → `https://<ip>:8766` → 浏览器「不安全」→「高级 / 详细信息」→「继续前往」 | 能 | 每台电脑每张证书一次 | 同一 Wi-Fi 没隔离；**公司 Wi-Fi 隔离时：头显和电脑都连同一个手机热点**，还是这条路（仍在局域网里，不上云） |
| **A · USB（兜底 / 专家在旁边）** | 头显 USB 连电脑，电脑运行 `adb forward tcp:8765 tcp:8765`，打开 `http://localhost:8765` | 能（localhost 是安全来源） | 电脑要有 adb、头显已授权这台电脑的 USB 调试（第一次会在头显里弹系统授权框） | 专家电脑就在现场、或开发调试；今天已验证 200 |
| B′ · 局域网 http | 直接用 `http://<ip>:8765` 看 | **不能** | — | 只看画面 + 画笔；顶栏显示 E4 一行「要说话请换安全地址」 |

- 说实话：USB 线意味着专家电脑在头显旁边，真正「远程」要靠同一网络（必要时手机热点）。USB 留着是因为它今天就能用、不怕任何网络策略。
- 公司管理的 Chrome / Edge 可能用策略禁掉「继续前往」（证书报错页没有那个链接）→ E6 里写一行「看不到『继续前往』？是公司浏览器策略拦了，请用手机热点 + 其他浏览器，或 USB」。spike 里用 Chrome 和 Edge 各试一次「接受自签证书后能否开麦」。

- 头显服务：`:8765` http（localhost 来的请求给专家台；局域网 IP 来的请求先给 E6 提示页）+ `:8766` https（同一页面 + `wss://…/ws`、`wss://…/audio`）。
- 证书：头显首次启动生成自签证书（CN `qianliyan.local`，SAN 含当时所有局域网 IP + `127.0.0.1`），存在应用私有目录，下次复用——同一台电脑只需「继续」一次；IP 变了才重签（再提示一次）。
- **未确认**：在 Android 上用 Ktor 生成并加载自签证书（`ktor-network-tls-certificates` 的 `buildKeyStore` 走 JKS；Android 上要 PKCS12/BKS）。研发站先花 ≤ 1 小时 spike；不成就**只上 A 路**：配对卡和 E4 都只推荐 USB，B 路从配对卡上去掉，其余设计不变。

### 2.3 用户看到什么（状态）

| 态 | 头显通话条（680×112 dp，6 键） | 专家台顶栏 |
| --- | --- | --- |
| S3 直播 · 通话中（改） | 「● 直播 · 通话中」；🎙 键亮（音量环随自己声音跳） | `[● 直播] 15 fps · 约 120 ms · USB`　…　`[🎙 静音 M]`（带音量环）`[结束]` |
| **S12 头显已静音**（新） | 🎙 键变 🎙̸（alert 底），状态「直播 · 我已静音」 | 状态签旁芯片「现场已静音」 |
| **S13 专家已静音**（新） | 状态「直播 · 专家已静音」（labelSecondary，不抢眼） | 🎙 键选中态 🎙̸ alert 底，文字「已静音」 |
| **S14 正在说话**（新，瞬时） | 对方说话：状态文字前固定的一格里出三格声波（passable 绿），**文字不变**（不随每句话闪）；自己说话：🎙 键音量环亮 | 对方说话：状态签旁三格声波 +「现场在说话」；自己说话：🎙 键音量环 |
| **S15 专家只能听**（新） | 状态「直播 · 专家只能听，不能说」labelSecondary（专家走 http 地址或浏览器拦了麦克风时） | 即 E4 / E5 |
| **S6a 语音断开**（新） | 状态「语音断开 · 正在重连」alert；🎙 禁用；画面、笔迹照常 | 🎙 禁用，状态签旁芯片「语音重连中」 |
| **S1u 配对 · 只推荐 USB**（新，条件态） | 仅当 https spike 失败：配对卡第三行改为「同一 Wi-Fi 打开它能看画面；要通话请用下面的 USB 方式」 | — |
| **S2s 配对 · 要去系统设置**（新） | 用户选过「不再询问」后：权限行按钮从 [授权] 变 [去设置]，点了打开系统应用权限页 | — |
| **E7 专家台 · 点一下恢复声音**（新） | — | 刷新页面自动重连后：取景框左下芯片「🔇 点一下恢复声音」，点一下恢复 |
| **S2m 配对 · 麦克风被拒**（新） | 配对卡麦克风行变 alert 色「麦克风未授权 · 只能看不能说」+ [授权] | （接入后）顶栏芯片「现场麦克风未开」 |
| **E4 专家台 · 麦克风要安全地址**（新） | — | 🎙 键禁用（LocalDisableAlpha 0.3）；顶栏下一行 alert 条「这个地址浏览器不给开麦克风。要说话：用 USB 打开 localhost:8765，或换 https 地址 →」；能听、能看、能画 |
| **E5 专家台 · 麦克风被拒**（新） | — | 🎙 键禁用；alert 条「浏览器拦了麦克风：点地址栏左侧 🔒 → 麦克风 → 允许，再点 [重试]」；能听、能看、能画 |
| **E6 专家台 · 安全地址提示页**（新） | — | 局域网 http 首次打开时：卡片「为了能通话，要换到安全地址」+ 三步图示（① 点 [继续] ② 浏览器提示「不安全」→ 点「高级」③ 点「继续前往」，这是因为证书是头显自己签的，只在你们这台头显和电脑之间用）+ [继续]（粉笔黄）+ 小字链接「先不通话，只看画面」 |
| S5 已暂停共享（改文案） | 「已暂停画面 · 麦克风开着」；🎙 键保持亮，提醒对方仍听得到 | 遮罩「⏸ 对方已暂停画面」+ 副文案「还能继续通话」 |
| S6 重连中（改） | 不变；通话同时中断，重连成功自动恢复（不需要再点） | 遮罩不变；🎙 键禁用 |
| S9 结束确认（不变） | 「结束会话并停止共享？」——「是」同时挂断 | — |

通话条状态文字一行，按优先级只显示最高的一条：画面不可用 > 重连中 > 已暂停画面 · 麦克风开着 > 语音断开 · 正在重连 > 正在打开摄像头 > 画面 3 秒前 > 直播 · 我已静音 > 直播 · 专家只能听，不能说 > 直播 · 专家已静音 > 直播 · 通话中。「对方在说话」不进文字，只用固定位置的声波；自己静音同时由 🎙 键（🎙̸ alert 底）表达，所以文字被更高优先级盖住时也看得出。

QA 组合截图（不单列状态，写进调试广播）：双方同时静音（S12 + S13）、摄像头和麦克风都被拒（S2 + S2m）、S8 [重试] 倒数中、状态签 `USB` 与 `Wi-Fi` 两版、「戴耳机」提示行。

### 2.4 交互（新增）

| 编号 | 名字 | 触发 | 确认 |
| --- | --- | --- | --- |
| T13 | 头显静音 / 取消 | 通话条 🎙（最左键） | 否 |
| T14 | 专家静音 / 取消 | 顶栏 🎙 或键盘 M | 否 |
| T15 | 首帧到达 | S10 收到第一张真实帧 → S3 | — |
| T16 | 画面重试 | S8 专家台 [重试] → S10 | 否 |
| T17 | 麦克风授权 | 配对卡 [授权]（头显）/ 浏览器许可后 [重试]（专家） | — |
| T18 | 换安全地址 | E6 [继续] → 浏览器证书提示 → 专家台 E1 | 否 |
| T19 | 自动接通 | T1 PIN 正确 → 同时打开 `/audio`；不响铃、不需要接听 | — |

## 3 · 配对卡改版（C11）

尺寸 640×384 → **640×448 dp**（max 720×420 → 720×480；物理约 0.56×0.39 m）。

```
┌──────────────────────────────────────────────────────────┐
│ ○ 千里眼 · 等待专家接入                                    │ titleMedium
│ 电脑浏览器打开                                             │ bodyMedium labelSecondary
│ 30.16.255.24:8765                    PIN  4 8 2 7        │ titleLarge ｜ displayLarge
│ ● 摄像头 · 已授权      ● 麦克风 · 已授权                    │ labelMedium passable
│ ──────────────────────────────────────────────────────── │
│ 连不上（公司 Wi-Fi 常这样）？                              │ bodyMedium labelSecondary
│ ① 头显和电脑都连同一个手机热点，再打开上面的地址              │ bodyMedium
│ ② 电脑就在旁边：USB 线连上，电脑运行                         │ bodyMedium
│   adb forward tcp:8765 tcp:8765   再打开 localhost:8765     │ 等宽 labelMedium，fillTertiary 底
└──────────────────────────────────────────────────────────┘
```

- 地址只写一个（不带 `http://`，好念）；局域网打开它会先到 E6 再转 https。热点换了网段，卡上的地址会跟着刷新（读当前 IP）。若 2.2 的 https spike 失败（S1u），「电脑浏览器打开」下加一行小字「同一网络打开它能看画面；要通话请用 ②」。
- 专家经 USB 连上时，状态签末尾显示 `USB`，经 Wi-Fi 显示 `Wi-Fi`（看 WebSocket 对端是否 127.0.0.1）。

## 4 · 通话条改版（C12）

`[● 状态文字 / 声波]  🎙  ✎  ↶  🗑  ◉  ⏻`——6 个 56 dp 键，🎙 放最左（通话里最常用、最需要盲点），⏻ 仍在最右。宽 616 → **680 dp**（min 600，max 760；物理约 0.42×0.07 m）。密度上限改为「≤ 6 键 + 1 行状态」。🎙 用 `sui-toggle-icon-button`；音量环是键外 2 dp 描边，透明度随电平（自定义绘制，不是控件）；声波三格是 `sui-text` 前的自定义 Canvas。

## 5 · 验证边界（门 B 明说）

| 能在模拟器验 | 只能真机（Swan）验 |
| --- | --- |
| 全部新状态的样子（调试广播强制 S10 / S11 / S8×4 原因 / S3d / S12 / S13 / S14 / S2m）、专家台 E4 / E5 / E6、静音消息往返、`/audio` 握手与断线重连、USB `localhost` 路径的页面 | **真实画面**（读回能否到 12–15 fps、单帧耗时、比例与内参、延迟常数）、**回声与 AEC**、嘴到耳延迟、`RECORD_AUDIO` 在头显上的系统弹窗、https 自签证书在 Android 上能否生成 / 被浏览器接受 |

研发站顺序：① 真机 spike（读回 60 s：960×540 与 512×512 各一次，记 fps / 单帧耗时 / camK，截一帧核对颜色与 alpha；`AcousticEchoCanceler.isAvailable()`；`setCommunicationDevice` 选扬声器后能否听到；https 证书生成 + Chrome / Edge 接受后能否 `getUserMedia`，1 小时内定去留）→ ② 再写全量。模拟器全程保留 SYNTH，但带 S3d 芯片。

## 6 · 清单与生命周期（增量）

- 权限：`+ RECORD_AUDIO`、`+ MODIFY_AUDIO_SETTINGS`（切通话模式）；`CAMERA` 已有。
- 清单：`+ <meta-data android:name="pico.spatial.use_experimental_api" android:value="1"/>`。
- 依赖：`+ com.pico.spatial.ml:securemr`、`+ com.pico.spatial.ml:readback`（BOM 6.1.9 管版本）；https 若 spike 成功再加 Ktor TLS 相关依赖。
- 生命周期：退后台 / 关 Stage / 结束会话 → 停 SpatialML 循环并按 1.2 顺序释放、停 `AudioRecord` / `AudioTrack`、释放 AEC / NS、`AudioManager.mode` 复原、关 `/ws` `/audio`、停 http / https 服务。
