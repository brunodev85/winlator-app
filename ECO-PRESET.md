# ECO（伊希歐之夢 / Emil Chronicle Online）· Winlator 完整优化配置指南

> 适配设备：
> - **手机**：小米 14 Pro（第三代骁龙8 · Adreno 750 · 16GB RAM · 3200×1440 6.73″）
> - **平板**：小米平板 7（骁龙 8S · Adreno · **8GB RAM**）
>
> 适用版本：本仓库 `eco-preset` 分支构建的 Winlator（含简体中文 / 日文语言包 + ECO 内置预设）

---

## 一、ECO 官方配置需求（原始 PC 版）

| 元件 | 最低配置 | 建议配置 |
|---|---|---|
| CPU | Pentium III 1.0 GHz | Pentium 4 3.0 GHz / 双核 |
| 内存 | 256 MB | 2 GB |
| 显卡 | Riva TNT / Radeon 7000 | GeForce 7300 GT / Radeon 9660 |
| 系统 | Windows 2000 | Windows XP / 7 / 10 |
| DirectX | 9.0c | 9.0c |
| 硬盘 | 5 GB | 5 GB |

**结论**：对现代手机/平板毫无压力。瓶颈不是性能，而是 **x86 → ARM 转译兼容性** 与 **DirectX 9 老接口**。DX9 缺失或 DXVK 选择不当，会出现「物件弹出画面、模型穿模、贴图乱飞」等渲染异常。

---

## 二、双设备推荐容器参数

| 参数 | 📱 小米14 Pro（16GB） | 📱 小米平板7（8GB） | 说明 |
|---|---|---|---|
| 屏幕分辨率 | 1280×720 | 1280×720 | ECO 原生 1024×768，720p 兼顾清晰与流畅 |
| 图形驱动 | Turnip (Vulkan) | Turnip (Vulkan) | 骁龙 Adreno 的 Vulkan 驱动 |
| 图形 API（DXWrapper） | **WineD3D** | **WineD3D** | ⚠️ **不要用 DXVK**！DXVK 会加重 ECO 渲染错乱、物件溢出 |
| Offscreen 渲染 | **Backbuffer** | **Backbuffer** | FBO 模式容易触发画面溢出 bug |
| Box64 预设 | **ECO（内置）** | **ECO（内置）** | 专为 DX9/x87 老游戏调优，已含 MESA 修复变量 |
| 显存模拟 | 4096 MB | **2048 MB** | 平板 8GB RAM 请维持 2048，勿调高 |
| CPU 核心 | 8 核全开 | 8 核全开 | 骁龙全核可用 |
| 音频驱动 | **PulseAudio** | **PulseAudio** | 解决没声音的问题 |
| CSMT | **关闭** | **关闭** | 多线程渲染对老网游容易画面错乱 |
| 画面效果 | 关闭 FXAA | 关闭 FXAA | 老游戏无需抗锯齿 |

---

## 三、必须安装的组件（容器内 System Tools）

| 组件 | 重要性 | 作用 |
|---|---|---|
| **DirectX 9.0c 完整运行库** | ⭐ 最重要 | 画面 bug（贴图乱飞、物件出地图）多数与 DX9 缺失有关 |
| Wine Mono | 必需 | .NET 组件，游戏界面/弹窗依赖 |
| Wine Gecko | 必需 | HTML 引擎组件 |
| **日文字体包** | 必需（日文版） | 解决日文显示为方块的问题 |

> 若出现「物件弹出、模型穿模、东西掉出画面」：先装 DirectX 9.0c 重启容器，再把 DXVK 切回 WineD3D、Offscreen 改 Backbuffer。

---

## 四、内置 ECO 预设参数说明

新增于 `Box64PresetManager`，位于「性能」与「自定义」之间，中文界面显示 **ECO（伊希歐之夢）**，日文界面显示 **ECO（エミル・クロニクル・オンライン）**：

| 环境变量 | 值 | 作用 |
|---|---|---|
| BOX64_DYNAREC_SAFEFLAGS | 2 | CALL/RET 标志位严格处理，防老游戏崩溃 |
| BOX64_DYNAREC_FASTNAN | 0 | 精确 NaN 生成，防画面/数值异常 |
| BOX64_DYNAREC_FASTROUND | 0 | 精确 x86 舍入，防花屏与数值错乱 |
| BOX64_DYNAREC_X87DOUBLE | 1 | x87 双精度模拟，ECO 这类老游戏必需 |
| BOX64_DYNAREC_BIGBLOCK | 1 | 保守块构建，兼容性优先 |
| BOX64_DYNAREC_STRONGMEM | 1 | 强内存模型，防乱序内存写入崩溃 |
| BOX64_DYNAREC_FORWARD | 128 | 中等前向距离 |
| BOX64_DYNAREC_CALLRET | 0 | CALL/RET 优化关闭，稳定优先 |
| BOX64_DYNAREC_WAIT | 1 | 等待 FillBlock 就绪 |
| BOX64_DYNAREC_NATIVEFLAGS | 1 | 尽可能用原生标志位（骁龙提速关键） |
| BOX64_DYNAREC_WEAKBARRIER | 1 | 弱内存屏障，兼顾稳定与速度 |
| **MESA_EXTENSION_MAX_YEAR** | **2003** | 🎯 限制新版 OpenGL 扩展，专门修复旧 DX9 游戏贴图乱飞、模型溢出（物件掉出画面） |

> 设计思路：**稳定（老游戏兼容）为底线，NATIVEFLAGS 提速为增益**。MESA_EXTENSION_MAX_YEAR=2003 已内置进预设，一键生效，无需手动加环境变量。

### 可选附加环境变量（容器 → 环境变量）

```
WINEESYNC=1          # 减少内存爆满（Winlator 默认已含，可确认存在）
```

---

## 五、游戏快捷方式单独参数（Shortcut → Advanced）

执行参数填入：
```
-force-gfx-direct
```
强制直接渲染，减少画面物件脱离场景的 bug。

---

## 六、内存优化（8GB 平板防爆内存）

1. 手机/平板后台全部清空，不留其他 APP
2. 容器内显存维持 2048MB（平板），不要调高
3. 关闭 CSMT（多线程渲染，老网游容易画面错乱）

---

## 七、排查顺序（物件飞出来时按此试）

1. **先装 DirectX 9.0c 运行库**，重启容器再入游戏
2. 图形 API 由 DXVK 转去 **WineD3D**，Offscreen 渲染改 **Backbuffer**
3. 确认 ECO 预设已生效（含 `MESA_EXTENSION_MAX_YEAR=2003`）
4. 快捷方式加 `-force-gfx-direct`
5. 如果仍穿模：**新建一个全新干净容器**，重装全部组件（旧容器残留 dll 会冲突）

> ⚠️ ECO 是 DirectX 9 老网游，**DXVK 对它的兼容性差**——很多人选了 DXVK 导致地图物件、NPC 贴图弹出画面。务必用 WineD3D。

---

## 八、黑屏组件级优化（Winlator 全组件）

黑屏 = DX9 的 3D/2D 图层渲染失败。按组件逐个排查：

| 组件 | 优化动作（容器内操作） | 针对黑屏的作用 |
|---|---|---|
| **CNC DDraw** | Wine 配置 → DDraw Wrapper 选「CNC DDraw」 | ⭐ 修复 2D 背景/天空盒/图层黑屏（ECO 2D 图层走 DDraw） |
| **Wine** | Wine 版本逐个试：9.x → 8.x → 7.x | ⭐ 不同 Wine 对 DX9 shader 兼容不同 |
| **DXVK / WineD3D** | 图形 API 互换测试 | ⭐ WineD3D 黑屏→切 DXVK；DXVK 弹物件→切回 WineD3D |
| **Mesa (Turnip/Zink/VirGL)** | 图形驱动 Turnip ↔ Zink ↔ Vortek 互换 | ⭐ Zink（OpenGL-on-Vulkan）对老 GL 游戏兼容好 |
| **Box64** | 已用 ECO 预设（含 MESA 修复） | 辅助，保持即可 |
| VKD3D | — | 不适用（DX12 转换，ECO 是 DX9） |
| GLIBC Patches | — | 系统层，无需调整 |

**推荐先试组合（按序）**：
1. 图形 API 切 **DXVK**（WineD3D 黑屏时）
2. DDraw Wrapper 开 **CNC DDraw**（修 2D 图层）
3. 图形驱动试 **Zink**
4. Wine 版本 9.x ↔ 8.x 互换
5. 仍黑 → 回 WineD3D + Turnip 交叉验证

> 进阶：容器内编辑 `C:\ProgramData\cnc-ddraw\ddraw.ini` 可细调 CNC DDraw 渲染后端。

---

## 九、日文显示修复

### 1. 界面语言（Winlator 本体）
设置 → 语言 → **日本語**，Winlator 界面即切换为日文（本版已内置 values-ja 日文语言包）。

### 2. 日文字体（解决方块字）
将 Windows 的日文字体复制进容器 C 盘 `C:\windows\Fonts\`：
- `msgothic.ttc`（MS Gothic）、`msmincho.ttc`（MS Mincho）—— 必装
- `YuGothM.ttc`（Yu Gothic）、`meiryo.ttc`（Meiryo）—— 推荐

### 3. Wine 容器日文区域（乱码修复）
容器环境变量添加：
```
LANG=ja_JP.UTF-8
LC_ALL=ja_JP.UTF-8
```
（Shift-JIS 编码客户端可试 `LANG=ja_JP.SJIS`）

### 4. 额外注册表设定（日文乱码仍存在时，容器 Wine 注册表编辑器执行）

```
[HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Nls\Language]
"InstallLanguage"="0411"
"DefaultLanguage"="0411"

[HKEY_CURRENT_USER\Control Panel\International]
"Locale"="00000411"
"LocaleName"="ja-JP"

[HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Nls\CodePage]
"ACP"="932"
"OEMCP"="932"
"MACCP"="10001"
```
> 0411 = 日文 (Japan)；932 = 日文 Shift-JIS 代码页。设置后重启容器生效。

### 5. 日文输入（可选）
按键映射里把「文本输入」绑定到输入法按钮，用 Android 日文 IME 输入（Winlator 会把 IME 文本送入 Wine）。

---

## 安装与运行步骤（双设备通用）

1. 安装本分支构建的 Winlator APK（已含中/日语言包 + ECO 预设）
2. 首次启动 → 下载安装 Wine 运行时（选 **9.x 稳定版**，不要选太新的 Wine 10，对 DX9 旧网游兼容性差）
3. **新建容器** → Box64 预设选「ECO（伊希歐之夢）」
4. 按第二节参数表设置（平板：显存 2048；手机：4096）
5. 安装第三节组件（DX9.0c / Mono / Gecko / 日文字体）
6. 把 ECO 客户端放入容器 C 盘或 D 盘 → 快捷方式加 `-force-gfx-direct` → 运行

---

## 源码位置

- 汉化：`app/src/main/res/values-zh/strings.xml`（325 条全量翻译）
- 日文：`app/src/main/res/values-ja/strings.xml`（325 条全量翻译）
- ECO 预设：`app/src/main/java/com/winlator/box64/Box64Preset.java` + `Box64PresetManager.java`
- 本分支：`eco-preset`（基于上游 main + 汉化 commit）
