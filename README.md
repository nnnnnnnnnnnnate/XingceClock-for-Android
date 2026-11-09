# 行测计时器 · Android 版（For Android）

一个给自己用的行测倒计时工具：清单中每一项 = 名称 + 分钟数，可以单独跑一项，
也可以按清单顺序整串“流水线”走完，每段到点自动响铃。

**平台：Android 10+（API 29+）**，原生 Android 实现，无第三方库、无广告。
Windows 版见 [XingceClock-for-Windows](https://github.com/nnnnnnnnnnnnate/XingceClock-for-Windows)。

- 包名：`com.kodama.xingcetimer`
- 版本：1.0（versionCode 1）
- 最低版本：Android 10（API 29），targetSdk 34
- 预编译 APK：`XingceTimer.apk`（约 46 KB）
- 已在 Lenovo IdeaPad Duet（Google Kodama，MT8183，1920×1200，Android 16）上验证

## 和 Windows 版的对应关系

| Windows 版 | Android 版 |
| --- | --- |
| 置顶的小计时窗口 | 前台服务 + 常驻通知（Android 不允许普通应用全局置顶） |
| 关闭窗口后停止 | 按 Home/切到别的应用不停止；点“停止”才停止 |
| 到点循环播放系统闹钟 | `MediaPlayer` + `USAGE_ALARM` 循环播放系统闹钟，失败时退回 `ToneGenerator`，同时振动 |
| 窗口置顶时屏幕常亮 | 计时页 `FLAG_KEEP_SCREEN_ON`；后台计时持有 `PARTIAL_WAKE_LOCK` |
| 系统时钟计时 | `SystemClock.elapsedRealtime()`，NTP 校时/改时间不会影响倒计时 |
| 双击清单项开始 | 点按选中，点大按钮开始；长按直接修改 |
| 464×502 固定窗口 | 横屏左右分栏，竖屏上下布局；计时页大字号自适应 |

## 界面

### 设置页（横屏）

- 左侧：闹钟清单、添加/修改/删除/上移/下移。
- 右侧：总项数、总时长、当前选中项，以及两个大按钮：
  - **单独走选中项**：只跑当前一项。
  - **流水线整串走完**：从第一项按顺序跑到最后一项。

### 计时页

- 全屏沉浸，隐藏状态栏和导航栏，屏幕常亮。
- 超大倒计时数字，自动适配横竖屏。
- 暂停/继续、跳过本段、停止。
- 流水线模式下显示“接下来：xxx（N 分钟）”。
- 到点响铃 5 秒后自动进入下一项；最后一项会一直响到点“知道了”。

## 数据

默认清单与 Windows 版一致：

```
常识判断    10
言语理解与表达 30
数量关系    15
判断推理    30
资料分析    30
涂卡检查    5
```

修改后会写入应用私有目录：

```
/data/data/com.kodama.xingcetimer/files/stages.txt
```

格式仍是 `名称<TAB>分钟`（UTF-8），和 Windows 版相同。卸载应用会清掉这份数据。

## 安装

预编译 APK 已在仓库根目录：

```bash
adb install -r XingceTimer.apk
```

也可以把 APK 拷到平板 `Download/`，在文件管理器里点开安装。

首次启动会请求通知权限（前台计时通知需要）。

## 编译

需要 JDK 17 和 Android SDK（platform 36 + build-tools 36.0.0）：

```powershell
$env:ANDROID_SDK = "C:\path\to\sdk"
$env:JAVA_HOME   = "C:\path\to\jdk-17"
powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
```

脚本直接调用 `aapt2 / javac / d8 / zipalign / apksigner`，不依赖 Gradle。
首次编译会生成 `debug.keystore`（口令 `android`，仅用于测试签名，不入库）。
自己重新编译后如果要覆盖安装，请保留同一个 keystore，否则会因签名不同失败。

## 源码结构

```
AndroidManifest.xml
build-apk.ps1
src/com/kodama/xingcetimer/
  MainActivity.java     设置页、清单编辑
  RunActivity.java      全屏计时页
  TimerService.java     前台服务、状态机、响铃、通知
  Stage.java            单个阶段
  StageStore.java       stages.txt 读写
res/
  layout/               竖屏布局
  layout-land/          横屏布局
  drawable/ values/     主题、颜色、图标
```

## 权限说明

- `POST_NOTIFICATIONS`：前台服务通知。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`：后台计时和响铃。
- `WAKE_LOCK`：锁屏后仍能到点响铃。
- `VIBRATE`：响铃时振动。
- 不申请网络、存储、定位等权限。

## 已知限制

- 设备没有 RTC 时，开机后系统时间会先回到构建时间；本应用使用 `elapsedRealtime`，不受影响。
- 闹钟音量跟随系统“闹钟”音量；如果闹钟音量为 0，只会振动。
- Android 的省电策略可能延迟后台应用，但前台服务 + WakeLock 已覆盖正常使用场景。
- 没有“全局置顶”是 Android 平台限制；用通知和全屏计时页替代。
