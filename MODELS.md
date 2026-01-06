# 模型资源配置指南

本文档详细说明如何获取和配置项目所需的模型文件和原生库文件。

## 📦 概述

由于模型文件体积较大（数百 MB 至数 GB），这些文件未包含在 Git 仓库中。您需要按照以下步骤手动下载并放置到指定位置。

---

## 📂 目录结构

项目需要以下两个主要目录的资源文件：

### 1. 模型文件目录

```
SherpaOnnxSimulateStreamingAsr/app/src/main/assets/
├── silero_vad.onnx                                          # 约 2 MB
├── 3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx      # 约 7 MB
├── sense-voice-rknn/
│   ├── model-10-seconds.rknn                               # 约 250 MB
│   └── tokens.txt                                          # 约 500 KB
└── helsinki-translation/zh-en/
    ├── encoder_model.onnx                                  # 约 300 MB
    ├── decoder_model.onnx                                  # 约 100 MB
    ├── decoder_with_past_model.onnx                        # 约 150 MB
    ├── source.spm                                          # 约 800 KB
    ├── target.spm                                          # 约 800 KB
    └── vocab.txt                                           # 约 1 MB
```

### 2. 原生库目录

```
SherpaOnnxSimulateStreamingAsr/app/src/main/jniLibs/arm64-v8a/
├── libsherpa-onnx-jni.so                                   # 约 15 MB
├── libhelsinki-onnx-jni.so                                 # 约 5 MB
├── libonnxruntime.so                                       # 约 8 MB
├── librknnrt.so                                            # 约 2 MB
├── librga.so                                               # 约 500 KB
└── cargs.h                                                 # 头文件（可选）
```

---

## 🔽 下载资源

### 方法一：从官方源下载（推荐）

#### 1. VAD 模型 - Silero VAD

**文件**: `silero_vad.onnx`

**下载地址**:
```bash
# 方式 1: 直接下载
wget https://github.com/snakers4/silero-vad/raw/master/files/silero_vad.onnx \
     -O SherpaOnnxSimulateStreamingAsr/app/src/main/assets/silero_vad.onnx

# 方式 2: 从 Sherpa-ONNX 仓库下载
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx \
     -O SherpaOnnxSimulateStreamingAsr/app/src/main/assets/silero_vad.onnx
```

**来源**: [Silero VAD 官方仓库](https://github.com/snakers4/silero-vad)

---

#### 2. 说话人识别模型 - 3D-Speaker

**文件**: `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`

**下载地址**:
```bash
# 从 ModelScope 下载（国内推荐）
wget https://www.modelscope.cn/models/damo/speech_campplus_sv_zh-cn_16k-common/resolve/master/campplus_cn_common.bin \
     -O SherpaOnnxSimulateStreamingAsr/app/src/main/assets/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx

# 或从 Sherpa-ONNX 预构建模型下载
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx \
     -O SherpaOnnxSimulateStreamingAsr/app/src/main/assets/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
```

**来源**:
- [ModelScope - 3D-Speaker](https://www.modelscope.cn/models/damo/speech_campplus_sv_zh-cn_16k-common)
- [Sherpa-ONNX Speaker Models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models)

---

#### 3. ASR 模型 - SenseVoice RKNN

**文件**: `sense-voice-rknn/model-10-seconds.rknn`, `sense-voice-rknn/tokens.txt`

**下载地址**:

> ⚠️ **注意**: RKNN 模型是针对 RK3576 芯片转换的，需要使用 RKNN Toolkit 2 转换原始 ONNX 模型。

**方式 1: 使用预转换的 RKNN 模型**（如果您有访问权限）
```bash
# 从您的私有存储或团队共享位置下载
# 示例（替换为实际地址）:
# scp user@server:/path/to/sense-voice-rknn.zip ./
# unzip sense-voice-rknn.zip -d SherpaOnnxSimulateStreamingAsr/app/src/main/assets/
```

**方式 2: 自行转换 ONNX 模型为 RKNN**

1. 下载原始 SenseVoice ONNX 模型:
   ```bash
   # 从 ModelScope 下载
   git clone https://www.modelscope.cn/iic/SenseVoiceSmall.git
   ```

2. 使用 RKNN Toolkit 2 转换:
   ```python
   # 安装 RKNN Toolkit 2
   pip install rknn-toolkit2

   # 转换脚本示例（需根据实际模型调整）
   from rknn.api import RKNN

   rknn = RKNN()
   rknn.config(target_platform='rk3576')
   rknn.load_onnx(model='sensevoice.onnx')
   rknn.build(do_quantization=True)
   rknn.export_rknn('./model-10-seconds.rknn')
   ```

3. 复制 tokens.txt:
   ```bash
   cp SenseVoiceSmall/tokens.txt \
      SherpaOnnxSimulateStreamingAsr/app/src/main/assets/sense-voice-rknn/
   ```

**来源**:
- [SenseVoice 模型](https://www.modelscope.cn/models/iic/SenseVoiceSmall)
- [RKNN Toolkit 2 文档](https://github.com/rockchip-linux/rknn-toolkit2)

---

#### 4. 翻译模型 - Helsinki-NLP

**目录**: `helsinki-translation/zh-en/`

**下载地址**:

```bash
# 创建目录
mkdir -p SherpaOnnxSimulateStreamingAsr/app/src/main/assets/helsinki-translation/zh-en

# 从 Hugging Face 下载（中文→英文模型）
# 方式 1: 使用 git-lfs
git lfs install
git clone https://huggingface.co/Helsinki-NLP/opus-mt-zh-en \
          /tmp/helsinki-zh-en

# 方式 2: 使用 wget 直接下载 ONNX 导出版本
# 注意：需要先使用 Optimum 库将 PyTorch 模型转换为 ONNX
pip install optimum[exporters]
optimum-cli export onnx --model Helsinki-NLP/opus-mt-zh-en \
                        --task translation \
                        helsinki-translation/zh-en/

# 复制必需文件
cp /tmp/helsinki-zh-en/*.onnx \
   SherpaOnnxSimulateStreamingAsr/app/src/main/assets/helsinki-translation/zh-en/
cp /tmp/helsinki-zh-en/*.spm \
   SherpaOnnxSimulateStreamingAsr/app/src/main/assets/helsinki-translation/zh-en/
cp /tmp/helsinki-zh-en/vocab.txt \
   SherpaOnnxSimulateStreamingAsr/app/src/main/assets/helsinki-translation/zh-en/
```

**ONNX 转换详细步骤**:

```python
from transformers import MarianMTModel, MarianTokenizer
import torch

# 加载模型
model_name = "Helsinki-NLP/opus-mt-zh-en"
model = MarianMTModel.from_pretrained(model_name)
tokenizer = MarianTokenizer.from_pretrained(model_name)

# 导出为 ONNX
torch.onnx.export(
    model.get_encoder(),
    torch.randint(0, 1000, (1, 128)),
    "encoder_model.onnx",
    input_names=["input_ids"],
    output_names=["last_hidden_state"],
    dynamic_axes={"input_ids": {0: "batch", 1: "sequence"}}
)

# decoder 和 decoder_with_past 类似导出...
```

**来源**:
- [Helsinki-NLP OPUS-MT 模型](https://huggingface.co/Helsinki-NLP)
- [Optimum ONNX 导出工具](https://huggingface.co/docs/optimum/exporters/onnx/usage_guides/export_a_model)

---

#### 5. 原生库文件 - Sherpa-ONNX JNI

**文件**: `libsherpa-onnx-jni.so`, `libonnxruntime.so`, `librknnrt.so`, 等

**下载地址**:

```bash
# 方式 1: 从 Sherpa-ONNX 官方预编译包下载
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.0/sherpa-onnx-v1.10.0-android.tar.bz2
tar -xvf sherpa-onnx-v1.10.0-android.tar.bz2

# 复制 ARM64 库文件
cp sherpa-onnx-v1.10.0-android/jniLibs/arm64-v8a/*.so \
   SherpaOnnxSimulateStreamingAsr/app/src/main/jniLibs/arm64-v8a/

# 方式 2: 从 RKNN 官方仓库获取 RKNN Runtime
# 下载 RKNN Runtime for RK3576
wget https://github.com/rockchip-linux/rknpu2/releases/download/v1.6.0/rknpu2_1.6.0_20231212.tar.bz2
tar -xvf rknpu2_1.6.0_20231212.tar.bz2

# 复制 RKNN 库
cp rknpu2/runtime/RK3576/Android/librknn_api/arm64-v8a/librknnrt.so \
   SherpaOnnxSimulateStreamingAsr/app/src/main/jniLibs/arm64-v8a/
```

**来源**:
- [Sherpa-ONNX Releases](https://github.com/k2-fsa/sherpa-onnx/releases)
- [RKNPU2 Runtime](https://github.com/rockchip-linux/rknpu2)

---

### 方法二：从网盘下载（快速部署）

如果您是团队成员或有访问权限，可以从以下网盘获取打包好的资源：

**百度网盘** / **阿里云盘** / **Google Drive**（请根据实际情况填写）

```
链接: https://pan.baidu.com/s/XXXXXX
提取码: XXXX

包含文件:
- sherpa-onnx-models.zip        # 所有 ONNX 模型
- rknn-models.zip               # RKNN 模型
- jniLibs-arm64.zip             # 原生库文件
```

**解压说明**:
```bash
# 解压到对应目录
unzip sherpa-onnx-models.zip -d SherpaOnnxSimulateStreamingAsr/app/src/main/assets/
unzip rknn-models.zip -d SherpaOnnxSimulateStreamingAsr/app/src/main/assets/
unzip jniLibs-arm64.zip -d SherpaOnnxSimulateStreamingAsr/app/src/main/jniLibs/arm64-v8a/
```

---

## ✅ 验证配置

下载并放置所有文件后，使用以下命令验证目录结构：

```bash
# 检查模型文件
ls -lh SherpaOnnxSimulateStreamingAsr/app/src/main/assets/
ls -lh SherpaOnnxSimulateStreamingAsr/app/src/main/assets/sense-voice-rknn/
ls -lh SherpaOnnxSimulateStreamingAsr/app/src/main/assets/helsinki-translation/zh-en/

# 检查原生库
ls -lh SherpaOnnxSimulateStreamingAsr/app/src/main/jniLibs/arm64-v8a/
```

**期望输出**:
```
# assets 目录应包含:
silero_vad.onnx (约 2 MB)
3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx (约 7 MB)

# sense-voice-rknn 目录:
model-10-seconds.rknn (约 250 MB)
tokens.txt (约 500 KB)

# helsinki-translation/zh-en 目录:
encoder_model.onnx (约 300 MB)
decoder_model.onnx (约 100 MB)
decoder_with_past_model.onnx (约 150 MB)
source.spm, target.spm, vocab.txt

# jniLibs/arm64-v8a 目录:
libsherpa-onnx-jni.so (约 15 MB)
libonnxruntime.so (约 8 MB)
librknnrt.so (约 2 MB)
librga.so (约 500 KB)
```

---

## 🔧 自定义模型配置

如果您想使用不同的模型，可以修改 `ModelConfig.kt`:

```kotlin
// 文件位置: app/src/main/java/com/k2fsa/sherpa/onnx/config/ModelConfig.kt

object ModelConfig {
    object Selection {
        // 修改这些常量指向您的模型文件
        const val VAD_MODEL_TYPE = 0  // 0=Silero, 1=TenVAD, 2=RKNN
        const val ASR_MODEL_TYPE = 100  // 100=SenseVoice-RKNN, 101=Whisper-RKNN
        const val SPEAKER_MODEL = "您的说话人模型.onnx"
        const val TRANSLATION_MODEL_DIR = "您的翻译模型目录"
    }
}
```

详细的模型配置说明请参考 [CLAUDE.md](./CLAUDE.md)。

---

## ❓ 常见问题

### Q1: 模型文件太大，下载很慢怎么办？

**A**:
- 使用国内镜像源（如 ModelScope）替代 Hugging Face
- 使用下载工具（如 aria2c）多线程下载
- 从团队网盘获取预打包文件

### Q2: RKNN 模型转换失败？

**A**:
- 确保使用与目标芯片匹配的 RKNN Toolkit 版本（RK3576 需要 Toolkit 2.x）
- 检查原始 ONNX 模型是否包含不支持的算子
- 参考 [RKNN 官方文档](https://github.com/rockchip-linux/rknn-toolkit2/tree/master/doc)

### Q3: 原生库加载失败？

**A**:
- 确认设备架构为 ARM64-v8a（`adb shell getprop ro.product.cpu.abi`）
- 检查所有 `.so` 文件是否完整且未损坏
- 查看 Logcat 日志获取详细错误信息

### Q4: 如何切换到 CPU 版本（不使用 RKNN）？

**A**:
修改 `ModelConfig.kt`:
```kotlin
const val VAD_MODEL_TYPE = 0     // 使用 Silero CPU 版本
const val ASR_MODEL_TYPE = 2     // 使用 Whisper CPU 版本（需下载对应模型）
```

---

## 📚 参考资源

- [Sherpa-ONNX 文档](https://k2-fsa.github.io/sherpa/onnx/)
- [RKNN Toolkit 2 使用指南](https://github.com/rockchip-linux/rknn-toolkit2/blob/master/doc/Rockchip_User_Guide_RKNN_Toolkit2_CN.pdf)
- [ONNX Runtime 文档](https://onnxruntime.ai/docs/)
- [Helsinki-NLP 翻译模型](https://github.com/Helsinki-NLP/Tatoeba-Challenge)
- [3D-Speaker 声纹识别](https://github.com/alibaba-damo-academy/3D-Speaker)

---

## 📞 技术支持

如遇到模型配置问题，请：
1. 查看项目 [Issues](https://github.com/YOUR_USERNAME/AI-real-time-ASR-Translate-SpeakerID/issues)
2. 提交新 Issue 并附上详细日志
3. 联系项目维护者

---

**注意**: 所有模型的使用需遵守各自的开源许可证。商业使用前请仔细阅读相关模型的许可协议。
