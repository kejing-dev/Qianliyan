# 千里眼 Qianliyan (remote assist)

Renamed from 借眼 Jieyan on 2026-10-20 (user). Only the shown names changed (app label, lobby title, expert page);
the package `io.embodit.jieyan`, the factory appId `jieyan`, the log tag `Jieyan`, the debug action
`io.embodit.jieyan.DEBUG` and the class names keep the old name on purpose.

See-what-I-see remote assistance for PICO OS 6 (Swan). Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage
(passthrough), package `io.embodit.jieyan`, v0.1.3. The worker wearing the headset shares the live camera
picture with an expert in a browser on the same LAN; the expert draws on the video and the chalk lines appear
at the same place on a head-locked transparent "visor glass" 1.0 m in front of the worker's eyes; the worker
can draw back in blue, and the expert sees it on the video.

Design package (binding): `design/design-doc.md` (v2, 2026-10-19), `design/networking.md` (link scheme A:
headset-hosted HTTP + WebSocket, MJPEG frames), `design/preview.html` (clickable prototype),
`C:/Users/Admin/Downloads/spatial-app-factory/apps/jieyan/design-package.json` (states S1–S9, E1–E3).
Archetype D, reference repo Spatial Ruler (`../spatial-ruler`: same Mixed Stage + panel-pool skeleton).

## What is built (v0.1.0, emulator-verified)

- Lobby card (S1 / S2): address `http://<ip>:8765`, 4-digit PIN (new per session), camera-permission row,
  one line of help; lazy-follow 1.2 m ahead.
- Call strip (S3–S9): live dot, status line, pen / undo / clear / share / end keys; inline end confirmation
  (S9) brings the strip 8 dp nearer; reconnecting (S6) runs a 2 dp alert progress line along the bottom.
- Visor glass: head-locked (`AnchorTarget.createCameraTarget()`), 1024 x 576 dp panel scaled to W/fx x H/fy
  metres from the camera intrinsics; both parties' strokes with a dark halo, the expert's pointer dot
  (700 ms), chalk-blue frame + chip while the worker's pen is on (the 4 % veil is the ECS quad `GlassVeil`
  behind the panel); press-and-drag on it is a blue stroke. The glass panel carries no material.
- Ktor CIO server on :8765 (`GET /` = `assets/expert.html`, `WS /ws`), one expert at a time, PIN gate with
  a 60 s lock after 5 wrong tries, reconnect with the same session within 30 s, JPEG frames `[u32 seq][u64 ts][jpeg]`.
- Expert page: PIN gate (E1), 16:9 viewfinder with frame + strokes + pointer, chips (对方画笔已开 / 画面 3 秒前),
  overlays (paused / reconnecting / error), chalk tray (3 colours, 3 widths, undo, clear, 8 s fade), snackbar
  with undo after clear (E2), end dialog (E3), ping/RTT + fps in the top bar, reconnect every 2 s.
- Camera: **synthetic source** (drawn workbench with a clock and a moving arm, 640 x 360 @ 12 fps, JPEG q70)
  on the emulator and, for now, on a device too. `VstReadbackCameraSource` holds the SpatialML Readback
  Mode contract and answers "not available" until the device station implements it.
- Debug broadcast `io.embodit.jieyan.DEBUG` (debug builds) to put the app in any design state; see below.

Not built / device only: VST readback (frame rate, latency, aspect, intrinsics -> glass alignment), head-lock
feel on a real head, CAMERA runtime permission on a headset (the lobby's 授权 key starts `PermissionActivity`;
on the emulator the synthetic source needs no permission and the row reads 已授权).

## Structure and why

```
app/src/main/java/io/embodit/jieyan/
  Main.kt                      DefaultStage { JieyanTheme { JieyanScreen() } }
  domain/                      Geometry (Vec3/Quat4/HeadPose/FacePanel), Session (Stroke, Phase, DataState,
                               CameraIntrinsics, Pin), Protocol (wire JSON + binary frame)   -- no SDK types
  platform/                    SpatialApplication, LaunchActivity, PermissionActivity (+ CameraPermission), JieyanLog
  platform/tracking            HeadTracker (HMDTrackingProvider, read once per frame)
  platform/camera              CameraSource (interface + factory), SyntheticCameraSource, VstReadbackCameraSource (stub)
  platform/net                 AssistServer (Ktor CIO: page + WebSocket, conflated frame channel), NetworkAddress
  ui/theme                     JieyanTheme (PicoTheme + 4 semantic roles), JieyanColors (the only colour literals),
                               JieyanTypography (Noto Sans + Noto Sans SC subset, type roles of the design)
  ui/session                   SessionViewModel (the state machine S1–S9, server + camera owner), SessionUiState /
                               SessionEvent, JieyanScreen (wiring: frame system, probe host, start/stop)
  ui/session/components        JieyanStage (SpatialView + the three AttachmentPanels), LobbyCard, CallStrip, VisorGlass
  ui/session/scene             JieyanScene (+ JieyanFrameSystem, LazyFollow), PanelRig (panel pool, ported from Ruler), SceneKit
  ui/session/debug             Probe (control bounds registry + host seam; no-ops in release)
app/src/debug/                 DebugCommandReceiver, DebugCommands
app/src/main/assets/expert.html   the expert console (single file, no external resources)
tools/                         dev.sh (pinned to emulator-5560), cap.sh (console screenshot), subset_cjk_font.py
```

One direction of data: panel controls / page messages / debug hooks -> `SessionViewModel.onEvent` or
`onMessage` -> `SessionUiState` -> panels (Compose) and `JieyanScene.render` (which panel stands where).
Per-frame work (head pose, lazy follow, facing) lives in `JieyanScene.onFrame` only (one registered `System`)
and never goes through Compose. Strokes are low-frequency UI content drawn by a Compose `Canvas` on the glass
panel; the worker's live stroke is batched to the page every 33 ms. The headset is the authority: the page
mirrors `state`.

## Runtime facts (emulator-5560, AVD PICO_6_1_Verified, SDK 6.1.9, 2026-10-20)

- Everything in Spatial Ruler's AGENTS.md "Runtime facts" about attachment panels applies here (PanelRig is a
  port): a panel parented under a disabled holder never appears; a parked panel below a head anchor steals the
  pointer; a panel composes its content only after it is in view; `faceViewer` writes the turn into the holder;
  1 dp = 0.8 mm at scale 1.
- The emulator has no camera: `SyntheticCameraSource` is chosen whenever `Build` says emulator, or the VST
  source is unavailable (always, in v0.1.0). The picture carries `SYNTH mm:ss.mmm #frame` for latency checks.
- Head lock: the glass hangs on a camera anchor (`AnchorComponent(AnchorTarget.createCameraTarget())`,
  offset (0, 0, -1.0) m, no facing rotation). The design's "ECS 每帧写 transform" is realised by the platform's
  camera target (same result, no one-frame lag); the frame system still drives the lazy-follow panels.
- Glass size: `kx = (W/fx) / (1280 dp * 0.0008)`, `ky = (H/fy) / (720 dp * 0.0008)`; with the synthetic
  intrinsics (fx = fy = 609.5 for 640 x 360) the glass is 1.05 x 0.59 m at 1 m. A device's intrinsics come from
  `cameraMatrixResult` of the readback pipeline (see `VstReadbackCameraSource`).
- Stage dialogs: `AlertDialog` / `SnackbarHost` do not work in a Stage (Ruler finding), so the end confirmation
  is inline on the strip and status hints are the strip's status line (design decision, not a workaround).
- Emulator frame rate idles at 1–4 fps; the first head poses are the origin, so the lazy-follow panels wait for
  a real pose before they park (`FOLLOW lobby park ...` in the log).
- Attachment panels render at density 2 (`bounds` -> `PANELPX`: 640 dp = 1280 px).
- **No backdrop material in the visor glass panel** (QA round 1, q1-1). With the pen chip drawn as
  `backgroundMaterial(Material.Thick)`, the whole full-view glass panel came up with an opaque black band
  over its lower part (its backdrop sampled black), 4 / 4 captures, sometimes the call strip's glass with it;
  the band's height varied (35–65 % of the glass) and did not depend on the panel size (1280 x 720 and
  1024 x 576 dp both showed it). Taking only the 4 % veil fill out of the panel did not help; replacing the
  chip's material by a solid pill (`JieyanColors.ChipInk`) did: 0 dark pixels in 12 + 10 + 9 captures. The veil
  stays an ECS quad (`GlassVeil`: TRANSPARENT, depth write off) so the panel has nothing over its whole area.
  Small glass panels (lobby card, call strip) keep `Material.Regular`.
- Launch-time flicker (emulator): within ~20 s after a (re)launch the call strip's `Material.Regular` glass was
  black in 2 of 36 captures and back on the next capture (4 → 9 → 16 s: ok, black, ok); 0 of 10 once warm.
  Shoot every state twice, >= 8 s apart; a strip that is black once and clean on the retake is this flicker.
- **A glass panel must keep redrawing** (QA round 2, q2-1, v0.1.3). `backgroundMaterial` is a property of the
  panel's graphics layer (`SpatialGraphicsLayerScope.setBackgroundMaterial`, set in `placeWithLayer`): the
  glass is composited into the panel's own frame. In the first seconds after a panel comes into view the
  emulator can present a frame partially (top drawn, the rest opaque black). The strip heals within a frame or
  two because its live dot redraws all the time (QA video 0:14–0:16); the static lobby card after ⏻ → 是 kept
  the black for 1.5 min. `glassRefresh()` right after `backgroundMaterial(...)` (lobby card, call strip)
  re-records the material layer every 500 ms; debug builds log `GLASS redraw panel= tick= draws=` (about
  1 draw / s on the emulator). Any new glass panel needs it too. A/B: refresh off → black from +10.6 s to
  +27 s (q2-1); on → 30 / 30 ⏻ → 是 cycles settle clean, 6 single black captures in 198, each clean on the next
  capture (≤ 2.7 s). Sparser ticks in the first seconds made it worse (black held ~5 s): keep 500 ms.
  Shooting S1 after ⏻ → 是: the card first shows +10–14 s later (emulator panel lag); shoot at ≥ +16 s and
  retake once — a single black capture that is clean on the retake is this transient, not q2-1.
- The expert page's full state travels on the welcome's own socket (`HelloResult.Welcome.state`): a
  `send()` posted from `onHello` raced the socket's registration and was dropped.
- Host port 8765 is usually taken (another session's forward): `tools/dev.sh web` forwards to host :18765.

## UI rules

- All 2D UI is SpatialUI inside `PicoTheme` (`JieyanTheme`). No Material / Material3 anywhere
  (`grep -rEf ../spatial-app-factory/bin/material-grep.txt app/src` must stay empty).
- Colours come from `PicoTheme.colorScheme` roles or `JieyanColors`; every `Text` passes `color`. Content on
  chalk yellow (`interaction`) is `InkOnChalk`.
- Copy lives in `res/values` (zh, default) and `res/values-en`; re-run `python tools/subset_cjk_font.py`
  whenever the Chinese strings change (it subsets `C:\Windows\Fonts\NotoSansSC-VF.ttf`).
- Design tag -> component: toggle-icon-button -> `ToggleIconButton` (pen, share), icon-button -> `IconButton`
  (undo, clear, end), button -> `Button` (是 / 否 / 授权), dot-badge -> `DotBadge` (live dot),
  linear-progress -> `LinearProgressIndicator` (reconnecting), chip -> glass pill with `backgroundMaterial`
  (the glass chip is content, not a control). Glass root panels: `clip(shape)` + `backgroundMaterial(Material.Regular)`.

## Build, run, verify (Git Bash; device commands are pinned to emulator-5560)

```bash
tools/dev.sh build            # assembleDebug -> .scratch/build.log (JAVA_HOME inlined)
tools/dev.sh install && tools/dev.sh relaunch
tools/dev.sh log 'STATE|WS|SERVER|CAM' 60
tools/dev.sh crash
tools/cap.sh NAME             # console screenshot -> .scratch/shots/NAME.png
tools/dev.sh web              # adb forward headset :8765 -> host :18765; open http://localhost:18765, enter the PIN from the lobby card
```

The emulator's PIN: `tools/dev.sh cmd dump` -> `DUMP {... "pin":"1234" ...}` (or read it off the screenshot).

## Debug commands (debug build only)

`adb -s emulator-5560 shell "am broadcast -a io.embodit.jieyan.DEBUG -p io.embodit.jieyan --es cmd NAME [--es s 'text'] [--es a 'numbers']"`
or `tools/dev.sh cmd NAME [text] [numbers]`. One `Jieyan` log line answers each.

| cmd | arguments | effect |
|---|---|---|
| `state` | s=S1..S9 | jump to the named design state (S3+ connect a simulated expert when no browser is on) |
| `expert` | s=connect\|disconnect | simulated expert joins (-> live) / leaves (-> reconnecting) |
| `stroke` | s=expert\|worker, a=x0,y0,x1,y1,… | a finished stroke (normalised 0..1); no numbers = a hook-shaped sample around the lamp |
| `pointer` | a=x,y (none: hide) | the expert's pointer dot |
| `pen` / `share` / `fade` | s=on\|off | pen, sharing, 8 s fade |
| `undo` / `clear` / `end` / `confirm` | `confirm` s=yes\|no | as the strip keys |
| `data` | s=normal\|stale\|error | picture state, sticky until `data normal` |
| `reconnect` | s=on\|off\|timeout | socket dropped / back / 30 s passed |
| `perm` | s=denied\|granted | camera permission result |
| `ui` | s=pen\|undo\|clear\|share\|end\|confirm_yes\|confirm_no\|grant | press a control by name |
| `dump` | | `DUMP {json}` (also files/dump.json) |
| `pose` / `panels` / `bounds` / `park` | | head pose / panel pool / control bounds / re-park the lazy-follow panels |

## QA screenshot list (design-package `states[]`; `tools/qa_shot.sh <id> ["cmd word numbers" ...]` waits 8 s, then shoots)

Panels lag 3–8 s behind a state change on the emulator. Every `state S3..S9` starts from the same base (live,
picture normal, no confirmation), so the states can be shot in any order.

| state | how to reach | what must be in the shot |
|---|---|---|
| S1 配对 | launch, or `state S1` | lobby card 1.2 m ahead: title, `http://<ip>:8765`, 4-digit PIN, 「摄像头 · 已授权」; no strip, no glass frame |
| S2 权限被拒 | `state S2` (or `perm denied`) | permission row in error red with 「摄像头权限未授权 · 专家将看不到画面」+ [授权] |
| S3 直播 | `state S3` (or connect a browser with the PIN) | strip 「● 直播 专家已连接」with the breathing dot; glass invisible; `stroke expert` puts a yellow hook on the glass, `pointer 0.5,0.5` a yellow dot |
| S4 我在画 | `state S4` | glass stays see-through (room visible behind it, no black) with a 2 dp blue frame + dark chip 「在玻璃上画 · 专家同步看到」; strip ✎ selected, status 「画笔开 · 在玻璃上画」; `stroke worker` draws blue |
| S5 已暂停 | `state S5` | dot off, status 「已暂停共享」in alert orange, share key shows the closed eye; ✎ ↶ 🗑 disabled (sample 4.3) |
| S6 重连中 | `state S6` | status 「重连中…」alert + ↻, pen / undo / clear disabled, progress line along the bottom of the strip |
| S7 画面过期 | `state S7` | status 「画面 3 秒前」alert (page: grey viewfinder + chip) |
| S8 画面不可用 | `state S8` | dot off, status 「画面不可用 · 读回失败」error red + ⚠, ✎ ↶ 🗑 disabled (sample 4.5; a pen that was on goes off) (page: error overlay) |
| S9 结束确认 | `state S9` | strip shows 「结束会话并停止共享？」+ [是] (chalk yellow) + [否]; strip 8 dp nearer |
| E1–E3 | browser at `http://localhost:18765` after `tools/dev.sh web` | gate card / snackbar after clear / end dialog (`<dialog>` is top layer: assert with `document.getElementById('end-dialog').open`) |

`tools/dev.sh cmd dump` reports `designState` for the assertion; `window.jieyan.state()` on the page mirrors the console.

## Log (tag `Jieyan`)

`STATE <S?> phase= why= pin= expert= pen= data= strokes=` · `SERVER started|stopped|address=` · `WS welcome|closed|dropped|pin wrong|hello rejected` ·
`CAM source=|synthetic start|stop|error` · `STROKE expert|worker|debug|undo|clear|unclear` · `PERM camera granted=` · `STAGE root|enter|disposed` ·
`SCENE attached|detached` · `PANEL bound id=` · `FOLLOW lobby|strip park|slide` · `FRAME n= head= fwd=` (every 5 s) · `CMD recv` · `DUMP {json}` ·
`POSE hmd …` · `PANEL id=… pos=… scale=…` · `CTRL panel= name= cx= cy= w= h=` · debug builds: `PTR panel= ev= x= y=`, `RIG slot id= shown|parked|stowed`, `GLASS redraw panel= tick= draws=`.

## Next steps / open points

- Device station: implement `VstReadbackCameraSource` (SpatialML Readback Mode, see the class comment), read
  `cameraMatrixResult` into `CameraIntrinsics`, measure submit -> readback at 640 x 360 (target >= 12 fps,
  < 40 ms per frame), check the glass against a known object at 1 m, and the CAMERA permission flow.
- Audio is out of scope (parallel phone call). Multi-expert, WebRTC (scheme B/C/D) are v2.
- Other AI sessions own `emulator-5554` (Codex), `emulator-5556` (Spatial Ruler) and `emulator-5558` (ThreeViews).
  Only ever use `emulator-5560` (AVD `PICO_6_1_Verified`).
