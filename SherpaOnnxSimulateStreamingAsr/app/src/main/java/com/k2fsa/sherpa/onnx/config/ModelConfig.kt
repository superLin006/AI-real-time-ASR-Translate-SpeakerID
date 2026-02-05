package com.k2fsa.sherpa.onnx.config

import android.content.Context
import java.io.File

/**
 * 统一配置管理
 * 所有模型选择、运行参数、业务逻辑参数都在这里配置
 */
object ModelConfig {

    // ========== 模型选择 ==========
    object Selection {
        /**
         * 模型服务器地址
         * 首次启动时从此地址下载模型文件
         */
        var MODEL_SERVER_URL = "http://your-model-server.com/models"

        /**
         * 获取模型存储路径
         * 优先从下载目录加载，不存在则从 assets 加载（向后兼容）
         */
        fun getModelBasePath(context: Context): String {
            return com.k2fsa.sherpa.onnx.download.ModelDownloadManager.Config.getModelCacheDir(context).absolutePath
        }

        /**
         * VAD 模型类型
         * 0 = silero_vad.onnx (CPU)
         * 1 = ten-vad.onnx (CPU)
         * 2 = silero-vad-v4-rk3576.rknn (RKNN)
         */
        const val VAD_MODEL_TYPE = 0

        /**
         * ASR 模型类型
         * 见 OfflineRecognizer.kt 的 getOfflineModelConfig() 方法
         * 常用选项：
         * 100 = sense-voice-rknn (RKNN)
         * 101 = whisper-base-rknn (RKNN)
         * 其他见代码注释
         */
        const val ASR_MODEL_TYPE = 100

        /**
         * 说话人识别模型
         */
        const val SPEAKER_MODEL = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"

        /**
         * 翻译模式
         * "BIDIRECTIONAL" = 双向翻译（中英互译，根据ASR语言检测自动选择方向）
         * "UNIDIRECTIONAL" = 单向翻译（不管输入什么语言，都用指定模型翻译）
         */
        const val TRANSLATION_MODE = "BIDIRECTIONAL"

        /**
         * 翻译模型配置
         *
         * 双向翻译模式 (TRANSLATION_MODE = "BIDIRECTIONAL"):
         *   - SOURCE_LANG1 = "en", TARGET_LANG1 = "zh"  → 加载 helsinki-translation/en-zh
         *   - SOURCE_LANG2 = "zh", TARGET_LANG2 = "en"  → 加载 helsinki-translation/zh-en
         *   - 根据ASR检测的语言自动选择翻译器
         *
         * 单向翻译模式 (TRANSLATION_MODE = "UNIDIRECTIONAL"):
         *   - 只使用 TRANSLATION_MODEL_DIR 指定的模型
         *   - 不检查语言，所有识别结果都用这个模型翻译
         *   - 示例：ko-en 模型，不管说韩语、英语还是日语，都按韩语翻译成英语
         */

        // 双向翻译配置（仅在 BIDIRECTIONAL 模式生效）
        const val SOURCE_LANG1 = "en"
        const val TARGET_LANG1 = "zh"
        const val SOURCE_LANG2 = "zh"
        const val TARGET_LANG2 = "en"

        // 单向翻译配置（仅在 UNIDIRECTIONAL 模式生效）
        const val TRANSLATION_MODEL_DIR = "helsinki-translation/ko-en"
    }
    
    // ========== 运行参数 ==========
    object Runtime {
        /**
         * 采样率 (Hz)
         */
        const val SAMPLE_RATE = 16000
        
        /**
         * VAD 窗口大小 (samples)
         */
        const val VAD_WINDOW_SIZE = 512
        
        /**
         * ASR 线程数
         */
        const val ASR_NUM_THREADS = 1
        
        /**
         * 翻译模型详细日志
         */
        const val TRANSLATION_VERBOSE = true
        
        /**
         * 说话人识别线程数
         */
        const val SPEAKER_NUM_THREADS = 1
    }
    
    // ========== 业务逻辑参数 ==========
    object Pipeline {
        /**
         * 最大说话人数量
         * 超过此数量后，新说话人统一标记为 "S"
         */
        const val MAX_SPEAKERS = 15
        
        /**
         * 说话人相似度阈值 (0.0 - 1.0)
         * 越高越严格
         */
        const val SPEAKER_THRESHOLD = 0.5f
        
        /**
         * 最小翻译间隔 (毫秒)
         * 用于防抖，避免过于频繁的翻译请求
         */
        const val MIN_TRANSLATION_INTERVAL = 500L
        
        /**
         * ASR 中间结果更新间隔 (毫秒)
         * 实时显示识别进度的间隔
         * 推荐值：200ms(流畅) / 400ms(平衡) / 600ms(省电)
         */
        const val ASR_INTERMEDIATE_INTERVAL = 400L
    }
    
    // ========== 缓存配置 ==========
    object Cache {
        /**
         * 翻译模型缓存最大大小 (字节)
         * 默认 500MB
         */
        const val MAX_TRANSLATION_CACHE_SIZE = 500 * 1024 * 1024L
        
        /**
         * 是否启用翻译结果缓存
         * 相同文本会直接返回缓存结果
         */
        const val ENABLE_TRANSLATION_CACHE = true
    }
    
    // ========== 特性开关 ==========
    object Features {
        /**
         * 是否启用翻译功能
         */
        const val ENABLE_TRANSLATION = true
        
        /**
         * 是否启用说话人识别
         */
        const val ENABLE_SPEAKER_ID = true
        
        /**
         * 是否启用实时翻译
         * true = 中间结果也会翻译
         * false = 只翻译最终结果
         */
        const val ENABLE_REALTIME_TRANSLATION = true
    }
}
