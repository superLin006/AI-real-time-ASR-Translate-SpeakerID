#!/bin/bash

###############################################################################
# 推送模型到应用外部存储目录（新的规范化目录结构）
###############################################################################

APP_ID="com.k2fsa.sherpa.onnx.simulate.streaming.asr"
BASE_DIR="/storage/emulated/0/Android/data/${APP_ID}/files/models"

echo "========================================="
echo "  推送模型到应用外部存储"
echo "========================================="
echo ""
echo "目标路径: ${BASE_DIR}"
echo ""

# 检查设备连接
if ! adb devices | grep -q "device$"; then
    echo "✗ 未检测到设备，请确保设备已连接"
    exit 1
fi

echo "✓ 设备已连接"
echo ""

# 创建目录结构
echo "1. 创建目录结构..."
adb shell mkdir -p "${BASE_DIR}/ASR/sense-voice-rknn"
adb shell mkdir -p "${BASE_DIR}/VAD"
adb shell mkdir -p "${BASE_DIR}/Speaker"
adb shell mkdir -p "${BASE_DIR}/Translation/zh-en"
adb shell mkdir -p "${BASE_DIR}/Translation/en-zh"
echo "   ✓ 目录创建完成"
echo ""

# 推送 ASR 模型
echo "2. 推送 ASR 模型 (~473MB)..."
if [ -f "app/src/main/assets/sense-voice-rknn/model-10-seconds.rknn" ]; then
    adb push app/src/main/assets/sense-voice-rknn/model-10-seconds.rknn "${BASE_DIR}/ASR/sense-voice-rknn/"
    adb push app/src/main/assets/sense-voice-rknn/tokens.txt "${BASE_DIR}/ASR/sense-voice-rknn/"
    echo "   ✓ ASR 模型推送完成"
else
    echo "   ⚠ ASR 模型文件不存在，跳过"
fi
echo ""

# 推送 VAD 模型
echo "3. 推送 VAD 模型 (~629KB)..."
if [ -f "app/src/main/assets/silero_vad.onnx" ]; then
    adb push app/src/main/assets/silero_vad.onnx "${BASE_DIR}/VAD/"
    echo "   ✓ VAD 模型推送完成"
else
    echo "   ⚠ VAD 模型文件不存在，跳过"
fi
echo ""

# 推送 Speaker 模型
echo "4. 推送 Speaker 模型 (~27MB)..."
if [ -f "app/src/main/assets/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx" ]; then
    adb push app/src/main/assets/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx "${BASE_DIR}/Speaker/"
    echo "   ✓ Speaker 模型推送完成"
else
    echo "   ⚠ Speaker 模型文件不存在，跳过"
fi
echo ""

# 推送 Translation 模型 (zh-en)
echo "5. 推送 Translation 模型 zh-en (~237MB)..."
if [ -d "app/src/main/assets/helsinki-translation/zh-en" ]; then
    adb push app/src/main/assets/helsinki-translation/zh-en/encoder_model.onnx "${BASE_DIR}/Translation/zh-en/"
    adb push app/src/main/assets/helsinki-translation/zh-en/decoder_model.onnx "${BASE_DIR}/Translation/zh-en/"
    adb push app/src/main/assets/helsinki-translation/zh-en/decoder_with_past_model.onnx "${BASE_DIR}/Translation/zh-en/"
    adb push app/src/main/assets/helsinki-translation/zh-en/source.spm "${BASE_DIR}/Translation/zh-en/"
    adb push app/src/main/assets/helsinki-translation/zh-en/target.spm "${BASE_DIR}/Translation/zh-en/"
    adb push app/src/main/assets/helsinki-translation/zh-en/vocab.txt "${BASE_DIR}/Translation/zh-en/"
    echo "   ✓ Translation zh-en 模型推送完成"
else
    echo "   ⚠ Translation zh-en 目录不存在，跳过"
fi
echo ""

# 推送 Translation 模型 (en-zh)
echo "6. 推送 Translation 模型 en-zh (~237MB)..."
if [ -d "app/src/main/assets/helsinki-translation/en-zh" ]; then
    adb push app/src/main/assets/helsinki-translation/en-zh/encoder_model.onnx "${BASE_DIR}/Translation/en-zh/"
    adb push app/src/main/assets/helsinki-translation/en-zh/decoder_model.onnx "${BASE_DIR}/Translation/en-zh/"
    adb push app/src/main/assets/helsinki-translation/en-zh/decoder_with_past_model.onnx "${BASE_DIR}/Translation/en-zh/"
    adb push app/src/main/assets/helsinki-translation/en-zh/source.spm "${BASE_DIR}/Translation/en-zh/"
    adb push app/src/main/assets/helsinki-translation/en-zh/target.spm "${BASE_DIR}/Translation/en-zh/"
    adb push app/src/main/assets/helsinki-translation/en-zh/vocab.txt "${BASE_DIR}/Translation/en-zh/"
    echo "   ✓ Translation en-zh 模型推送完成"
else
    echo "   ⚠ Translation en-zh 目录不存在，跳过"
fi
echo ""

# 修复权限（重要！应用需要能读取这些文件）
echo "7. 修复文件权限..."
adb shell "chmod -R 755 ${BASE_DIR}/"
adb shell "find ${BASE_DIR}/ -type f -exec chmod 644 {} \;"
echo "   ✓ 权限修复完成"
echo ""

# 验证
echo "8. 验证文件..."
adb shell "ls -lh ${BASE_DIR}/ && \
           ls -lh ${BASE_DIR}/ASR/sense-voice-rknn/ && \
           ls -lh ${BASE_DIR}/VAD/ && \
           ls -lh ${BASE_DIR}/Speaker/ && \
           ls -lh ${BASE_DIR}/Translation/zh-en/ && \
           ls -lh ${BASE_DIR}/Translation/en-zh/"

echo ""
echo "========================================="
echo "✓ 模型推送完成！"
echo "========================================="
echo ""
echo "提示："
echo "  - 应用启动时会自动检测这些文件"
echo "  - 如果文件存在则直接加载"
echo "  - 如果文件缺失则从网络下载"
echo ""
