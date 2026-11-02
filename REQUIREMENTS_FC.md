# AutoNightEnh 项目需求基线

## 1 约定
计划阶段，确认率达不到95%，就要找我确认，让我选择；
输出设计文档，设计文档中不明确的地方标注TODO，后续处理；
设计文档应该有架构图、流程图、协议字段
调测中间文件放入项目根目录的.tmp目录下
注意把调测过程我，我修正的或者落地的经验记录下到调测经验文档，便于后续继承不再走弯路；
临时文件放入本项目的.tmp目录下
本文档只能人工修改，是后续需求，代码，文档的基准，有差异地方需要AI提出，人工确认如何处理；
功能做好，不需要编译，告诉我，即可，我来编译。

## 2. 项目边界与模块

### 2.1 项目定位与边界

GWV6HP-APP 是一个 Android 健康检测服务项目：通过摄像头采集车内座位图像，将图像帧送入本地健康检测 SDK，并通过 AIDL 向外部调用方提供检测控制、实时状态、最终结果和错误回调。

项目内负责：

- Android 服务生命周期、前台服务和 AIDL 接口；
- 前后摄像头打开、关闭、帧回调和异常恢复；
- 座位到摄像头、SDK 模式和 ROI 区域的路由；
- 健康 SDK 的初始化、启动、停止、释放和运行时配置；
- 实时 `HealthState`、最终 `HealthResult` 和错误事件的汇总与转发；
- 手动单座位检测和自动多座位轮询检测。

项目不负责：

- `AutoHealthSdk_1.1.5_release.aar` 内部算法、模型推理和指标计算实现；
- `AutoCameraLib_1.0.8_release.aar` 及设备厂商 Camera HAL 的底层实现；
- 外部调用方的业务界面、用户身份和业务数据存储；
- 修改 AIDL 契约之外的跨应用协议。

### 2.2 模块职责

| 模块/边界 | 职责 | 不应承担的职责 |
|---|---|---|
| `app` | 服务端、摄像头集成、SDK 编排、检测任务、状态和回调广播 | 不在应用层重写 SDK 算法或绕过任务生命周期 |
| `HealthDetectServer` | 导出前台 `Service`，实现 AIDL 控制入口和远程回调广播 | 不直接处理摄像头帧和健康指标聚合 |
| `HealthDetectCenter` | 协调 `CameraManager`、`HealthDetectTask`、`AutoDetectManager`、`StateManager` | 不把座位策略散落到多个调用方 |
| `HealthDetectTask` | 执行单座位检测，负责座位路由、SDK 输入、结果有效性、实时状态和最终结果 | 不负责跨进程协议实现 |
| `CameraManager` | 管理前/后摄像头生命周期、帧回调、打开异常和恢复 | 不负责 SDK 指标计算或 AIDL 广播 |
| `AutoDetectManager` | 管理座位列表、当前座位、座位间切换和自动检测延迟 | 不直接操作摄像头或 SDK |
| `SdkConfig`、`ConfigJson`、`FileUtil` | 准备 `autohealth` 资源、license、模型和运行时 `meta.json` 配置 | 不改变摄像头生命周期 |
| `healthdetectlib` | 外部调用方使用的控制侧绑定和 AIDL 命令封装 | 不复制服务端检测逻辑 |
| `healthdetectlistenerlib` | 外部调用方使用的监听侧绑定、回调注册和 JSON 转换 | 不决定服务端检测状态 |

### 2.3 稳定集成边界

- `IHealDetectApiInterface` 和 `IHealthDetectCallback` 是跨进程稳定契约，状态和结果以 JSON 字符串传输。
- 服务端、控制侧 SDK、监听侧 SDK 的职责边界不得因单项功能修改而互相侵入。
- 摄像头、SDK、任务和 AIDL 回调的创建、停止、释放顺序必须可追踪；异常路径也必须释放已获取资源。
- `forPad` 和 `forCar` 是现有构建 flavor，平台差异应通过现有配置和相机路由处理，不得在业务调用方复制平台判断。




## 3. 算法链路

### 3.1 主链路

```text
外部调用方
  -> healthdetectlib.HealthDetectManager
  -> AIDL IHealDetectApiInterface
  -> HealthDetectServer
  -> HealthDetectCenter
  -> HealthDetectTask
       -> SdkConfig 更新座位 ROI
       -> HealthDetectManager 初始化健康 SDK
       -> CameraManager 打开对应摄像头
       -> CameraManager.onFrameData
       -> HealthDetectTask.setFrameData
       -> AutoHealth SDK
       -> HealthDetectCenter.onHealthResult
       -> HealthDetectTask 结果有效性判断与聚合
  -> HealthDetectServer
  -> IHealthDetectCallback
```

### 3.2 启动与输入约束

1. 调用 `startHealthDetect(seat)` 时创建一个 `HealthDetectTask`；调用 `startAutoDetect(seats)` 时由 `AutoDetectManager` 逐个启动座位任务。
2. `HealthDetectTask.startInternal()` 必须先根据座位更新 SDK 配置，再初始化健康 SDK，最后打开对应摄像头。
3. 健康 SDK 初始化使用应用 `filesDir/autohealth/config` 下的运行时资源，并设置 VIN、帧格式和输入尺寸。
4. 当前 SDK 输入契约为 RGB 摄像头图像、`NV21` 格式、`1920x1080`；修改格式、尺寸或 stride 时，必须同时检查摄像头回调、转换链路和 SDK `setFrameConfig`。
5. 只有当前任务所属摄像头的帧才能送入当前任务；前后摄像头帧不得交叉路由。

### 3.3 座位、摄像头和 ROI 路由

| 座位 | 摄像头 | SDK 模式 | `1920x1080` 输入 ROI |
|---|---|---|---|
| `front_left`、`front_right` | 前摄像头 | `front_right` 为 `DRIVER_SEAT_MODE`；`front_left` 为 `PASSENGER_SEAT_MODE` | driver 模式写入右半区 `x=960..1920`；passenger 模式写入左半区 `x=0..960` |
| `back_left`、`back_right` | 后摄像头 | `back_left` 为 `DRIVER_SEAT_MODE`；`back_right` 为 `PASSENGER_SEAT_MODE` | driver 模式写入右半区 `x=960..1920`；passenger 模式写入左半区 `x=0..960` |

座位路由必须集中在 `HealthDetectTask`、`Constants` 和 `SdkConfig` 的现有边界内维护。新增座位时，必须同时定义摄像头、SDK 模式、ROI 和自动检测行为。

### 3.4 结果处理与生命周期

- SDK 回调 `onHealthResult` 收到有效结果后，`HealthDetectTask` 才能进入健康指标聚合；无效结果、无脸、ROI 无效必须进入人脸丢失计时路径。
- 当前单次检测计时窗口为 `34s`，最后 `20s` 才收集实时状态和最终结果所需的有效指标。
- 实时状态由 `HealthDetectTask` 生成，经服务端 `onDetectState` 转换为 AIDL `onHealthStateUpdate`，只有指标值为正时才向上层回调。
- 检测窗口结束后，按已收集数据聚合心率、心率变异性、呼吸率、血氧、血压和压力等指标，生成 `HealthResult` 并通过 `onFinishResult` 上报。
- 持续无有效人脸约 `10s` 时，任务必须上报 `ErrorCode.FACE_FAILED` 和 `face lost`，停止当前检测；自动检测模式随后切换到下一个座位。
- 正常结束、手动停止、人脸失败、SDK 错误和摄像头异常恢复都必须停止算法工作并释放 SDK；正常手动停止还必须关闭对应摄像头并恢复服务状态。
- 摄像头异常恢复可以保留当前任务，先释放 SDK，待摄像头重新打开后重新初始化 SDK，再继续当前座位检测；恢复失败必须上报错误并清理任务。

### 3.5 算法链路验证要求

- 调测必须能分别证明：服务已绑定、任务已创建、SDK 已初始化、摄像头已打开、帧已送入 SDK、SDK 已回调、实时状态/最终结果或错误已上报。
- 只看到 SDK 或任务内部日志，不能直接证明外部 listener 已收到回调；必须继续核对 `HealthDetectServer` 和 `IHealthDetectCallback` 链路。
- 任何帧格式、尺寸、ROI、座位路由或资源释放变更，都必须至少验证一个前排座位、一个后排座位，以及手动检测和自动检测路径。


## 4. 调测、构建与验证


## 5. 编译
- 不需要你帮我编译，功能完成之后告诉我。


## 6. AI文档
放置在项目根目录的docs目录下；

### 7 ADB Wi-Fi 快速连接
查询设备 IP：

```powershell
adb shell ip route
```
从输出中读取类似 `src 192.168.1.23` 的地址。备用命令：
```powershell
adb connect <设备IP>:5555
```
断开连接：
```powershell
adb disconnect <设备IP>:5555
```

#### 8 新需求
客户那边可能会更换摄像头id , 我们如果提供一个可以配置的cameraid， 这个方案怎么提供比较好呢？


### 9 debug 页面
adb shell am start -n com.senseauto.healthdetect/.ui.DebugActivity

### 10 是否开始视频录制
开启：

adb shell settings put global healthdetect_debug_record_mp4 1

关闭：

adb shell settings put global healthdetect_debug_record_mp4 0

查询当前状态：

adb shell settings get global healthdetect_debug_record_mp4

视频保存路径
/data/data/com.senseauto.healthdetect/files/
/data/user/0/com.senseauto.healthdetect/files/







