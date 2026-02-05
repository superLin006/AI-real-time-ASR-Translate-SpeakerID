# 模型下载配置指南

本文档说明应用的模型下载和管理机制。

---

## 📋 目录

- [核心机制](#核心机制)
- [按需下载](#按需下载)
- [模型存储路径](#模型存储路径)
- [配置说明](#配置说明)
- [调试模式](#调试模式)
- [服务器搭建](#服务器搭建)

---

## 核心机制

应用启动时会：
1. 检查本地是否已有所需模型
2. 如果模型缺失，从配置的服务器 URL 下载
3. 下载完成后，初始化并加载模型

**优点**：
- ✅ 应用体积小（无需打包大型模型文件）
- ✅ 只下载需要的模型（按需下载）
- ✅ 支持模型更新（删除旧模型重新下载）

---

## 按需下载

应用根据 `ModelConfig.kt` 中的配置，**只下载需要的模型**，而不是一次性下载所有模型。

### 下载规则

#### 1. ASR 模型（根据 `ASR_MODEL_TYPE`）

```kotlin
const val ASR_MODEL_TYPE = 100  // SenseVoice RKNN
```

| 类型 | 模型名称 | 下载的文件 | 大小 |
|------|---------|-----------|------|
| 100 | SenseVoice RKNN | `ASR/sense-voice-rknn/model-10-seconds.rknn`<br>`ASR/sense-voice-rknn/tokens.txt` | 473MB<br>308KB |

#### 2. VAD 模型（根据 `VAD_MODEL_TYPE`）

```kotlin
const val VAD_MODEL_TYPE = 0  // Silero VAD
```

| 类型 | 模型名称 | 下载的文件 | 大小 |
|------|---------|-----------|------|
| 0 | Silero VAD | `VAD/silero_vad.onnx` | 629KB |

#### 3. Speaker 模型（根据 `ENABLE_SPEAKER_ID`）

```kotlin
const val ENABLE_SPEAKER_ID = true
```

**条件**：只有当 `ENABLE_SPEAKER_ID = true` 时才下载

**文件**：`Speaker/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` (27MB)

#### 4. Translation 模型（根据翻译模式）

##### 双向翻译模式

```kotlin
const val TRANSLATION_MODE = "BIDIRECTIONAL"
const val SOURCE_LANG1 = "en"
const val TARGET_LANG1 = "zh"
const val SOURCE_LANG2 = "zh"
const val TARGET_LANG2 = "en"
```

**下载**：
- `Translation/en-zh/` 目录下的 6 个文件（237MB）
- `Translation/zh-en/` 目录下的 6 个文件（237MB）

**总计**：约 474MB

##### 单向翻译模式

```kotlin
const val TRANSLATION_MODE = "UNIDIRECTIONAL"
const val TRANSLATION_MODEL_DIR = "helsinki-translation/ko-en"
```

**下载**：只下载 `Translation/ko-en/` 目录下的 6 个文件（237MB）

**条件**：只有当 `ENABLE_TRANSLATION = true` 时才下载

---

## 模型存储路径

### 存储位置

```
/storage/emulated/0/Android/data/com.k2fsa.sherpa.onnx.simulate.streaming.asr/files/models/
```

这个路径由 `context.getExternalFilesDir(null)/models` 生成，应用卸载后会自动删除。

### 目录结构

```
models/
├── ASR/
│   └── sense-voice-rknn/
│       ├── model-10-seconds.rknn  (473MB)
│       └── tokens.txt              (308KB)
├── VAD/
│   └── silero_vad.onnx            (629KB)
├── Speaker/
│   └── 3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx  (27MB)
└── Translation/
    ├── zh-en/                      (中文→英文)
    │   ├── encoder_model.onnx      (50MB)
    │   ├── decoder_model.onnx      (89MB)
    │   ├── decoder_with_past_model.onnx  (86MB)
    │   ├── source.spm              (786KB)
    │   ├── target.spm              (788KB)
    │   └── vocab.txt               (982KB)
    └── en-zh/                      (英文→中文)
        └── ... (同上)
```

---

## 配置说明

### 配置模型服务器

编辑 `ModelConfig.kt` 中的 `MODEL_SERVER_URL`：

```kotlin
object Selection {
    var MODEL_SERVER_URL = "http://your-model-server.com/models"
}
```

### 配置示例

#### 完整配置（双向翻译 + Speaker）

```kotlin
object Selection {
    const val ASR_MODEL_TYPE = 100
    const val VAD_MODEL_TYPE = 0
    const val TRANSLATION_MODE = "BIDIRECTIONAL"
    const val SOURCE_LANG1 = "en"
    const val TARGET_LANG1 = "zh"
    const val SOURCE_LANG2 = "zh"
    const val TARGET_LANG2 = "en"
}

object Features {
    const val ENABLE_TRANSLATION = true
    const val ENABLE_SPEAKER_ID = true
}
```

**下载大小**：约 975MB (ASR + VAD + Speaker + 双向翻译)

#### 最小配置（不启用翻译）

```kotlin
object Features {
    const val ENABLE_TRANSLATION = false
    const val ENABLE_SPEAKER_ID = false
}
```

**下载大小**：约 474MB (仅 ASR + VAD)

#### 单向翻译

```kotlin
object Selection {
    const val TRANSLATION_MODE = "UNIDIRECTIONAL"
    const val TRANSLATION_MODEL_DIR = "helsinki-translation/ko-en"
}

object Features {
    const val ENABLE_TRANSLATION = true
}
```

**下载大小**：约 738MB (ASR + VAD + Speaker + 单向翻译)

---

## 调试模式

在开发调试时，可以使用 ADB 手动推送模型到设备，跳过网络下载。

### 使用推送脚本

```bash
cd SherpaOnnxSimulateStreamingAsr
bash push_models.sh
```

脚本会：
1. 从 `app/src/main/assets/` 读取模型文件
2. 推送到设备的 `/storage/emulated/0/Android/data/.../files/models/`
3. 自动修复文件权限（重要！应用需要可读权限）

### 手动推送示例

```bash
# 推送 ASR 模型
adb push models/ASR/sense-voice-rknn/model-10-seconds.rknn \
  /storage/emulated/0/Android/data/com.k2fsa.sherpa.onnx.simulate.streaming.asr/files/models/ASR/sense-voice-rknn/

# 修复权限（必须执行！）
adb shell chmod -R 755 /storage/emulated/0/Android/data/com.k2fsa.sherpa.onnx.simulate.streaming.asr/files/models/
adb shell find /storage/emulated/0/Android/data/com.k2fsa.sherpa.onnx.simulate.streaming.asr/files/models/ -type f -exec chmod 644 {} \;
```

⚠️ **权限说明**：通过 ADB 推送的文件默认所有者是 `shell`，应用无法直接读取。必须使用 `chmod` 修改权限为 `644`（所有人可读）。

### 验证推送成功

```bash
adb shell ls -lR /storage/emulated/0/Android/data/com.k2fsa.sherpa.onnx.simulate.streaming.asr/files/models/
```

检查：
- 目录权限应为 `drwxr-xr-x` (755)
- 文件权限应为 `-rw-r--r--` (644)

---

## 服务器搭建

### 服务器目录结构要求

你的模型服务器需要提供所有可能的模型文件，应用会根据配置只下载需要的部分：

```
http://your-model-server.com/models/
├── ASR/
│   └── sense-voice-rknn/
│       ├── model-10-seconds.rknn
│       └── tokens.txt
├── VAD/
│   └── silero_vad.onnx
├── Speaker/
│   └── 3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
└── Translation/
    ├── zh-en/
    │   ├── encoder_model.onnx
    │   ├── decoder_model.onnx
    │   ├── decoder_with_past_model.onnx
    │   ├── source.spm
    │   ├── target.spm
    │   └── vocab.txt
    ├── en-zh/
    │   └── ... (同上)
    ├── ko-en/
    │   └── ... (其他翻译方向)
    └── ... (根据需要添加更多翻译方向)
```

### 使用 Nginx 搭建示例

#### 1. 准备模型文件

```bash
mkdir -p /var/www/models
cd /var/www/models

# 创建目录结构
mkdir -p ASR/sense-voice-rknn
mkdir -p VAD
mkdir -p Speaker
mkdir -p Translation/zh-en
mkdir -p Translation/en-zh

# 复制模型文件到对应目录
cp your-models/model-10-seconds.rknn ASR/sense-voice-rknn/
cp your-models/tokens.txt ASR/sense-voice-rknn/
# ... 其他文件
```

#### 2. 配置 Nginx

```nginx
server {
    listen 80;
    server_name your-model-server.com;

    location /models/ {
        alias /var/www/models/;
        autoindex on;  # 可选：显示目录索引

        # 允许大文件下载
        client_max_body_size 500M;

        # CORS 支持（如果需要）
        add_header Access-Control-Allow-Origin *;
    }
}
```

#### 3. 测试下载

```bash
# 测试单个文件下载
curl -I http://your-model-server.com/models/VAD/silero_vad.onnx

# 应该返回 200 OK 和文件大小
```

---

## 故障排查

### 1. 下载失败

**症状**：应用启动时显示 "Failed to download models"

**可能原因**：
- 服务器 URL 不可访问
- 网络权限未授予应用
- 文件不存在或路径错误

**解决方法**：
1. 检查 `MODEL_SERVER_URL` 是否可访问
2. 确认应用已授予 `INTERNET` 权限
3. 查看 logcat 日志：`adb logcat -s ModelDownloadManager`

### 2. 模型检测失败（文件存在但检测为缺失）

**症状**：手动推送模型后，应用仍然尝试下载

**可能原因**：
- 文件大小不匹配（代码中配置的大小与实际文件大小不一致）
- 文件权限问题（应用无法读取）

**解决方法**：
1. 检查文件权限：`adb shell ls -l /storage/.../models/`
2. 确保目录为 `755`，文件为 `644`
3. 运行 `bash push_models.sh` 会自动修复权限

### 3. 翻译模型加载失败

**症状**：ASR 和 VAD 正常，但翻译不工作

**可能原因**：
- 翻译模型文件缺失或损坏
- `context` 参数未正确传递

**解决方法**：
1. 检查 `Translation/zh-en/` 和 `Translation/en-zh/` 目录下是否有 6 个文件
2. 查看日志：`adb logcat -s HelsinkiONNXKV`

---

## 更新模型

如果需要更新模型：

### 方法1：删除旧模型（推荐）

```bash
adb shell rm -rf /storage/emulated/0/Android/data/com.k2fsa.sherpa.onnx.simulate.streaming.asr/files/models
```

下次启动时会重新下载。

### 方法2：手动推送新模型

```bash
bash push_models.sh
```

### 方法3：在代码中清理

调用 `ModelDownloadManager.clearAllModels(context)`

---

## 添加新模型

### 添加新的 ASR 模型

#### 1. 在 `ModelDownloadManager.kt` 中添加模型定义

```kotlin
when (ModelConfig.Selection.ASR_MODEL_TYPE) {
    100 -> {
        // SenseVoice RKNN (已有)
    }
    101 -> {
        // 添加新的 Whisper RKNN
        models.add(ModelFile(
            "whisper-base.rknn",
            "ASR/whisper-rknn/whisper-base.rknn",
            300000000  // 300MB（实际大小）
        ))
        models.add(ModelFile(
            "tokens.txt",
            "ASR/whisper-rknn/tokens.txt",
            50000
        ))
    }
}
```

#### 2. 在服务器上添加对应的模型文件

```
models/ASR/whisper-rknn/
├── whisper-base.rknn
└── tokens.txt
```

#### 3. 在应用中设置 ASR_MODEL_TYPE = 101

```kotlin
const val ASR_MODEL_TYPE = 101  // 使用 Whisper
```

应用会自动下载 Whisper 模型，而不是 SenseVoice。

### 添加新的翻译方向

如果需要添加新的翻译方向（如日语→英语），只需：

#### 1. 在服务器上添加模型文件

```
models/Translation/ja-en/
├── encoder_model.onnx
├── decoder_model.onnx
├── decoder_with_past_model.onnx
├── source.spm
├── target.spm
└── vocab.txt
```

#### 2. 在应用中配置

```kotlin
const val TRANSLATION_MODE = "UNIDIRECTIONAL"
const val TRANSLATION_MODEL_DIR = "helsinki-translation/ja-en"
```

⚠️ **注意**：如果新翻译方向的模型文件大小与 `zh-en`/`en-zh` 不同，需要在 `ModelDownloadManager.kt` 的 `getTranslationModelFiles()` 函数中添加新的 case。

---

## 注意事项

⚠️ **首次启动时间**：首次启动需要下载模型，时间取决于网络速度（1-5 分钟）

⚠️ **配置改变后**：如果修改了 ASR 类型或翻译方向，需要删除旧模型重新下载

⚠️ **模型服务器**：确保服务器上有所有可能需要的模型文件

⚠️ **权限问题**：通过 ADB 推送模型时，必须修改文件权限为 `644`，否则应用无法读取

⚠️ **文件大小验证**：应用会验证文件大小，确保 `ModelDownloadManager.kt` 中配置的大小与实际文件大小完全一致
