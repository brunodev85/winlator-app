# ECO（伊希歐之夢 / Emil Chronicle Online）· Winlator 优化配置指南

> 适配设备：**小米 14 Pro**（第三代骁龙8 · Adreno 750 · 16GB RAM · 3200×1440 6.73″）
> 适用版本：本仓库 `eco-preset` 分支构建的 Winlator（含简体中文汉化 + ECO 内置预设）

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

**结论**：对现代手机毫无压力。瓶颈不是性能，而是 **x86 → ARM 转译兼容性** 与 **DirectX 9 老接口**。

---

## 二、小米 14 Pro 推荐容器参数

| 参数 | 推荐值 | 说明 |
|---|---|---|
| 屏幕分辨率 | 1280×720 | ECO 原生 1024×768，720p 兼顾清晰与流畅 |
| 图形驱动 | Turnip (Vulkan) | Adreno 750 的 Vulkan 驱动，性能最佳 |
| DX Wrapper | **WineD3D** | ECO 为 DX9 老游戏，WineD3D 兼容最稳；若花屏再试 DXVK |
| Box64 预设 | **ECO（内置新增）** | 本版新增预设，专为 DX9/x87 老游戏调优 |
| 显存 | 4096 MB | 16GB 内存的 ¼，老游戏绰绰有余 |
| CPU 核心 | 全部（8 核） | 骁龙8 Gen3 全核可用 |
| 音频驱动 | ALSA | 默认即可 |
| 画面效果 | 关闭 FXAA | 老游戏无需抗锯齿，减少开销 |

---

## 三、内置 ECO 预设参数说明

新增于 `Box64PresetManager`，位于「性能」与「自定义」之间，中文界面显示为 **ECO（伊希歐之夢）**：

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
| BOX64_DYNAREC_NATIVEFLAGS | 1 | 尽可能用原生标志位（骁龙8 提速关键） |
| BOX64_DYNAREC_WEAKBARRIER | 1 | 弱内存屏障，兼顾稳定与速度 |

> 设计思路：**稳定（老游戏兼容）为底线，NATIVEFLAGS 提速为增益**，介于官方 CONSERVATIVE 与 INTERMEDIATE 之间。

---

## 四、安装与运行步骤

1. 安装本分支构建的 Winlator APK（已含简体中文 + ECO 预设）
2. 首次启动 → 按提示下载安装 Wine 运行时
3. **新建容器** → 高级设置里 **Box64 预设选「ECO（伊希歐之夢）」**
4. 按上表设置分辨率 1280×720、显存 4096MB、图形驱动 Turnip
5. 把 ECO 客户端/私服登录器放入容器 C 盘或 D 盘 → 运行

---

## 五、常见问题排查

| 现象 | 处理 |
|---|---|
| 花屏 / 贴图错误 | 容器里 DX Wrapper 切换 WineD3D ↔ DXVK |
| 启动闪退 | Wine 配置里 Windows 版本切到 XP 兼容；勾选管理员权限 |
| 长时间挂机变卡 | 32 位程序内存泄漏所致，重启游戏即可 |
| 手柄无响应 | 输入控制里开启「外接手柄」并绑定按键 |

---

## 源码位置

- 汉化：`app/src/main/res/values-zh/strings.xml`（325 条全量翻译）
- ECO 预设：`app/src/main/java/com/winlator/box64/Box64Preset.java` + `Box64PresetManager.java`
- 本分支：`eco-preset`（基于上游 main + 汉化 commit 1af66ee）
