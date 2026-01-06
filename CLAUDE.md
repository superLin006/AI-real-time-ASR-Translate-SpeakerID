# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**AI-real-time-ASR-Translate-SpeakerID (LANGO)** is a real-time Android application for Automatic Speech Recognition (ASR) + Translation + Speaker Identification, optimized for RK3576 and MTKG520 processors with RKNN (NPU) acceleration.

**Tech Stack:**
- Language: Kotlin with JNI bindings to native C/C++ libraries
- UI: Jetpack Compose with Material 3
- Build: Gradle 8.2.2 with Kotlin DSL
- AI Runtime: ONNX Runtime + RKNN Runtime (NPU)
- Target: Android 21+ (API 21-34)

## Build Commands

All commands should be run from the `SherpaOnnxSimulateStreamingAsr/` directory.

### Development Build
```bash
cd SherpaOnnxSimulateStreamingAsr
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

### Clean Build
```bash
./gradlew clean
```

### Install to Device
```bash
./gradlew installDebug
# or for release:
./gradlew installRelease
```

### Run Tests
```bash
# Unit tests
./gradlew test

# Instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest
```

### Check Dependencies
```bash
./gradlew dependencies
```

### Lint and Code Quality
```bash
./gradlew lint
```

## Architecture Overview

### Core Processing Pipeline

The application follows a **coroutine-based streaming pipeline** architecture:

```
AudioRecorder → VAD → ASR → [Speaker ID + Translation]
                 ↓      ↓           ↓
              Speech  Text    [Speaker + Translated Text]
              Segments         ↓
                          UI (Home Screen)
```

**Key Flow:**
1. `AudioRecorder` captures raw audio (16kHz, mono PCM)
2. `SpeechPipeline` orchestrates all processing stages
3. `VAD` (Voice Activity Detection) identifies speech segments
4. `OfflineRecognizer` (ASR) transcribes speech to text
   - Generates intermediate results every 400ms for real-time UI updates
   - Produces final results when speech segment completes
5. `SpeakerEmbeddingExtractor` + `SpeakerEmbeddingManager` identify speakers
6. `HelsinkiONNXKV` translates text (with caching and debouncing)
7. Results flow to `HomeScreen` via callbacks

### Component Responsibilities

#### `SimulateStreamingAsr.kt` (Singleton)
Global model lifecycle manager:
- Initializes all AI models on app startup
- Provides thread-safe access to model instances
- Methods: `extractEmbedding()`, `translateText()`, `identifyOrRegisterSpeaker()`
- Releases resources via `releaseAll()`

#### `SpeechPipeline.kt`
Core audio processing coordinator:
- Manages VAD → ASR → Speaker ID → Translation flow
- Handles intermediate vs. final result logic
- Translation debouncing (500ms minimum interval)
- Translation result caching
- Callbacks: `onIntermediateResult()`, `onFinalResult()`, `onTranslationUpdate()`

#### `ModelConfig.kt`
Centralized configuration with 5 sub-objects:
- **Selection**: Model type choices (VAD, ASR, Speaker, Translation)
- **Runtime**: Execution parameters (sample rate, threads, window size)
- **Pipeline**: Business logic (max speakers, thresholds, intervals)
- **Cache**: Translation cache settings
- **Features**: Feature flags (enable translation, speaker ID, real-time translation)

#### `MainActivity.kt`
App entry point:
- Handles RECORD_AUDIO permission
- Initializes all models via `initializeAllModels()`
- Sets up Compose navigation

#### `HomeScreen` (Home.kt)
Main UI with recording controls and results display:
- Record button to start/stop audio capture
- Scrollable list of transcription results
- Each result shows: timestamp, speaker name, original text, translated text
- Visual distinction between intermediate (gray) and final (white) results
- Copy-to-clipboard functionality

### Model Files Location

All models are in `app/src/main/assets/`:
- `silero_vad.onnx` - Voice Activity Detection
- `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` - Speaker identification
- `helsinki-translation/zh-en/` - Translation models (Chinese to English)
  - `encoder_model.onnx`
  - `decoder_model.onnx`
  - `decoder_with_past_model.onnx`
  - `source.spm`, `target.spm`, `vocab.txt`
- `sense-voice-rknn/model-10-seconds.rknn` - ASR model (RKNN format for NPU)

### JNI Native Libraries

Located in `app/src/main/jniLibs/arm64-v8a/`:
- `libsherpa-onnx-jni.so` - JNI wrapper for Sherpa-ONNX (ASR, VAD, Speaker)
- `libhelsinki-onnx-jni.so` - JNI wrapper for Helsinki translation
- `libonnxruntime.so` - ONNX Runtime inference engine
- `librknnrt.so` - RKNN Runtime for RK3576 NPU acceleration
- `librga.so` - RGA graphics library

## Development Guidelines

### Adding a New Model

1. Place model files in `app/src/main/assets/[model-name]/`
2. Update `androidResources.noCompress` in `app/build.gradle.kts` if using new file extension
3. Create Kotlin wrapper class (see `OfflineRecognizer.kt` as reference)
4. Add configuration to `ModelConfig.kt` (Selection section)
5. Initialize in `MainActivity.initializeAllModels()`
6. Add to `SimulateStreamingAsr` singleton if shared globally
7. Integrate into `SpeechPipeline` processing flow if needed

### Modifying Pipeline Logic

Key file: `SpeechPipeline.processAudioStream()` (lines 69-223)

**Important state management:**
- `added` flag tracks whether intermediate result was added to UI
- `currentResultIndex` tracks current result position
- If `added == true`: update existing result on VAD completion
- If `added == false`: create new result on VAD completion

**Callback usage:**
- `onIntermediateResult()` - For real-time updates (every 400ms during speech)
- `onFinalResult()` - When VAD detects speech end
- `onTranslationUpdate()` - Async translation completion

### Changing Model Selection

Edit `ModelConfig.Selection`:
- `VAD_MODEL_TYPE`: 0=Silero CPU, 1=TenVAD, 2=Silero RKNN
- `ASR_MODEL_TYPE`: 100=SenseVoice-RKNN, 101=Whisper-RKNN (see `OfflineRecognizer.getOfflineModelConfig()` for full list)
- `SPEAKER_MODEL`: Path to speaker ID model in assets
- `TRANSLATION_MODEL_DIR`: Translation model directory in assets

### Performance Tuning

Edit `ModelConfig.Runtime`:
- `SAMPLE_RATE`: Audio sample rate (default 16kHz)
- `VAD_WINDOW_SIZE`: VAD processing window (default 512 samples = 32ms)
- `ASR_NUM_THREADS`: ASR inference threads
- `SPEAKER_NUM_THREADS`: Speaker ID inference threads

Edit `ModelConfig.Pipeline`:
- `MAX_SPEAKERS`: Maximum speakers before auto-labeling as "S"
- `SPEAKER_THRESHOLD`: Speaker similarity threshold (0.0-1.0, higher = stricter)
- `MIN_TRANSLATION_INTERVAL`: Translation debounce (default 500ms)
- `ASR_INTERMEDIATE_INTERVAL`: Real-time ASR update interval (default 400ms)

### UI Modifications

Main UI is in `app/src/main/java/com/k2fsa/sherpa/onnx/simulate/streaming/asr/screens/Home.kt`:
- `HomeScreen` composable is the main entry point
- Results displayed in `LazyColumn` with `DisplayResult` items
- Navigation routes defined in `NavRoutes.kt`

## Environment Requirements

### Build Environment
- **Java**: 17 (OpenJDK 17)
- **Android SDK**: Location configured in `gradle.properties` as `sdk.dir`
- **Gradle**: 8.2.2 (wrapper included)
- **Kotlin**: 1.9.0
- **Android Gradle Plugin**: 8.2.2

### Runtime Requirements
- **Minimum SDK**: 21 (Android 5.0)
- **Target SDK**: 34 (Android 14)
- **Permissions**: RECORD_AUDIO, INTERNET, ACCESS_NETWORK_STATE
- **Architecture**: ARM64-v8a (64-bit ARM)
- **Heap**: Large heap enabled in manifest

### Gradle Configuration
Located in `SherpaOnnxSimulateStreamingAsr/gradle.properties`:
- JVM max memory: 2GB (`-Xmx2048m`)
- Parallel builds enabled
- Build caching enabled
- AndroidX and Jetifier enabled
- Aliyun Maven mirrors configured for China network optimization

## Common Development Patterns

### Coroutine Usage
All async operations use Kotlin coroutines:
- `Dispatchers.IO` - File I/O and network (translation)
- `Dispatchers.Default` - CPU-intensive work (audio processing)
- `Dispatchers.Main` - UI updates (Compose state changes)

### State Management
UI state managed with Compose `mutableStateListOf()` and `mutableStateOf()`:
- Results list in `HomeScreen` is observable
- UI automatically recomposes on state changes

### Resource Management
Models must be explicitly released:
- Call `SimulateStreamingAsr.releaseAll()` in `MainActivity.onDestroy()`
- Release individual streams after each inference: `stream.release()`
- Stop pipeline before releasing models: `pipeline.stop()`

## Project Structure

```
app/src/main/java/com/k2fsa/sherpa/onnx/
├── simulate/streaming/asr/
│   ├── MainActivity.kt              # App entry point
│   ├── SimulateStreamingAsr.kt      # Model lifecycle manager (singleton)
│   ├── screens/                     # Compose UI screens
│   └── ui/theme/                    # Material 3 theme
├── pipeline/
│   ├── SpeechPipeline.kt            # Core audio processing pipeline
│   └── AudioRecorder.kt             # Audio capture wrapper
├── config/
│   └── ModelConfig.kt               # Centralized configuration
└── [Model wrappers]
    ├── OfflineRecognizer.kt         # ASR wrapper
    ├── Vad.kt                       # VAD wrapper
    ├── SpeakerEmbeddingExtractor.kt # Speaker embedding
    ├── SpeakerEmbeddingManager.kt   # Speaker ID manager
    ├── Helsinki.kt                  # Translation wrapper
    └── OnlineStream.kt              # Audio stream wrapper
```

## Version Information

Current version from `app/build.gradle.kts`:
- **versionCode**: 20251113
- **versionName**: "1.12.17"

Application ID: `com.k2fsa.sherpa.onnx.simulate.streaming.asr`
