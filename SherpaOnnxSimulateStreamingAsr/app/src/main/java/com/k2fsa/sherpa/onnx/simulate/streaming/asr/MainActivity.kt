package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.k2fsa.sherpa.onnx.config.ModelConfig
import com.k2fsa.sherpa.onnx.download.ModelInitializer
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens.HelpScreen
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens.HomeScreen
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.ui.theme.SimulateStreamingAsrTheme
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

const val TAG = "sherpa-onnx-sim-asr"

// MTK 模型目录名（现在支持从 assets 直接加载！）
const val MTK_MODEL_DIR = "sense-voice-mtk"
private const val REQUEST_RECORD_AUDIO_PERMISSION = 200

@Suppress("DEPRECATION")
class MainActivity : ComponentActivity() {
    private val permissions: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)

    // MTK 模型的文件系统路径（复制后）
    var mtkModelPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimulateStreamingAsrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        // 🔥 MTK 现在支持从 assets 直接加载，不再需要复制到文件系统！
        // if (ModelConfig.Selection.ASR_MODEL_TYPE == 1000) {
        //     mtkModelPath = copyMtkModelToFilesystem()
        // }

        // 统一初始化所有模块
        initializeAllModels()
    }

    /**
     * 将 MTK 模型从 assets 复制到文件系统
     * MTK NPU 需要真实文件路径，不能直接从 assets 加载
     */
    private fun copyMtkModelToFilesystem(): String? {
        try {
            val destDir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            Log.i(TAG, "Copying MTK model to: $destDir")

            copyAssetFolder(MTK_MODEL_DIR, "$destDir/$MTK_MODEL_DIR")

            Log.i(TAG, "MTK model copied successfully")
            return destDir
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy MTK model", e)
            return null
        }
    }

    /**
     * 递归复制 assets 目录到文件系统
     */
    private fun copyAssetFolder(assetPath: String, destPath: String) {
        try {
            val assets = this.assets.list(assetPath)
            if (assets.isNullOrEmpty()) {
                // 是文件，直接复制
                copyAssetFile(assetPath, destPath)
            } else {
                // 是目录，创建目录并递归复制
                val dir = File(destPath)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                for (asset in assets) {
                    val subAssetPath = if (assetPath.isEmpty()) asset else "$assetPath/$asset"
                    val subDestPath = "$destPath/$asset"
                    copyAssetFolder(subAssetPath, subDestPath)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset folder: $assetPath", e)
        }
    }

    /**
     * 复制单个 asset 文件到文件系统
     */
    private fun copyAssetFile(assetPath: String, destPath: String) {
        try {
            val destFile = File(destPath)
            // 如果文件已存在且大小相同，跳过复制
            if (destFile.exists()) {
                val assetSize = this.assets.open(assetPath).use { it.available().toLong() }
                if (destFile.length() == assetSize) {
                    Log.d(TAG, "Skipping (already exists): $assetPath")
                    return
                }
            }

            Log.i(TAG, "Copying: $assetPath -> $destPath")
            this.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset file: $assetPath", e)
        }
    }
    
    /**
     * 统一初始化所有模型（支持多种加载方式）
     */
    private fun initializeAllModels() {
        lifecycleScope.launch {
            // 第一步：确保模型已准备好（如果使用 download 模式，会在这里下载）
            val modelsReady = ModelInitializer.ensureModelsReady(
                context = this@MainActivity,
                onProgress = { current, total, fileName ->
                    Log.i(TAG, "Downloading model $current/$total: $fileName")
                    // Toast 必须在主线程调用
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Downloading: $fileName ($current/$total)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )

            if (!modelsReady) {
                Log.e(TAG, "Failed to prepare models")
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to download models. Please check your network connection.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            // 第二步：使用全模块并行初始化加载模型
            try {
                Log.i(TAG, "Starting model initialization...")

                SimulateStreamingAsr.initAllModelsParallel(
                    assetManager = this@MainActivity.assets,
                    application = this@MainActivity.application,
                    cacheDir = this@MainActivity.cacheDir,
                    externalModelBasePath = mtkModelPath,
                    maxCacheSize = ModelConfig.Cache.MAX_TRANSLATION_CACHE_SIZE
                )

                // 初始化后的状态检查和提示
                if (SimulateStreamingAsr.isTranslatorReady() && ModelConfig.Features.ENABLE_TRANSLATION) {
                    val mode = ModelConfig.Selection.TRANSLATION_MODE
                    val modeText = when (mode) {
                        "BIDIRECTIONAL" -> {
                            val lang1 = "${ModelConfig.Selection.SOURCE_LANG1}↔${ModelConfig.Selection.TARGET_LANG1}"
                            val lang2 = "${ModelConfig.Selection.SOURCE_LANG2}↔${ModelConfig.Selection.TARGET_LANG2}"
                            "Bidirectional ($lang1, $lang2)"
                        }
                        "UNIDIRECTIONAL" -> {
                            val modelDir = ModelConfig.Selection.TRANSLATION_MODEL_DIR.substringAfterLast("/")
                            "Unidirectional ($modelDir)"
                        }
                        else -> mode
                    }
                    Toast.makeText(this@MainActivity, "Translation: $modeText", Toast.LENGTH_SHORT).show()
                } else if (ModelConfig.Features.ENABLE_TRANSLATION) {
                    Toast.makeText(this@MainActivity, "Translation unavailable", Toast.LENGTH_LONG).show()
                }

                Toast.makeText(this@MainActivity, "✓ Models initialized successfully!", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Model initialization completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Initialization error", e)
                Toast.makeText(
                    this@MainActivity,
                    "Initialization error: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionToRecordAccepted = if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }

        if (!permissionToRecordAccepted) {
            Log.e(TAG, "Audio record is disallowed")
            Toast.makeText(
                this,
                "This App needs to access the microphone",
                Toast.LENGTH_SHORT
            )
                .show()
            finish()
        }

        Log.i(TAG, "Audio record is permitted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 释放所有资源
        Log.i(TAG, "Releasing all resources...")
        SimulateStreamingAsr.releaseAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        "LANGO : Real-time ASR + Translation",
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
        content = { padding ->
            Column(Modifier.padding(padding)) {
                NavigationHost(navController = navController)

            }
        },
        bottomBar = {
            BottomNavigationBar(navController = navController)
        }
    )
}

@Composable
fun NavigationHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = NavRoutes.Home.route) {
        composable(NavRoutes.Home.route) {
            HomeScreen()
        }

        composable(NavRoutes.Help.route) {
            HelpScreen()
        }
    }
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    NavigationBar {
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        NavBarItems.BarItems.forEach { navItem ->
            NavigationBarItem(selected = currentRoute == navItem.route,
                onClick = {
                    navController.navigate(navItem.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(imageVector = navItem.image, contentDescription = navItem.title)
                }, label = {
                    Text(text = navItem.title)
                })
        }
    }
}
