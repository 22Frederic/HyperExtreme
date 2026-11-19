// `java` 在这个脚本里被 Gradle 的 java 插件扩展占用，用别名 import 绕开，
// 否则 `java.util.Properties` 会被解析成 `JavaPluginExtension.util`。
import java.util.Properties as JavaProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 发布签名：机密值不进版本库，改从仓库外的 keystore.properties 读取。
// 没有该文件时 release 退回 debug 签名 —— 保证 clone 下来就能构建（见 README）。
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    JavaProperties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "com.frederic22.hyperextreme"
    compileSdk = 37
    // miuix 0.9.5 的 AAR 元数据要求 minCompileSdk=37 / minCompileMinorSdk=2
    //（`aar-metadata.properties` 里写死的，编译期检查、不影响运行行为）。
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    // native 子工程（仅 arm64-v8a）：现在只剩空间壁纸的 PatchMatch 填充（纯 C）。
    // 2026-11-15 起「系统桌面桥」（home_bridge）随隐藏状态栏功能一并删除。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.frederic22.hyperextreme"
        minSdk = 33
        targetSdk = 37
        versionCode = 1060000
        versionName = "1.0 Beta6"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 发布签名（本地模块项目，没有对外服务）：keystore/release.jks
        // 生成方式（口令只写进 keystore.properties，不要进版本库）：
        //   keytool -genkeypair -keystore keystore/release.jks -alias <别名> \
        //     -keyalg RSA -keysize 2048 -validity 10000
        // 没有 keystore.properties 时不创建这个配置，release 退回 debug 签名。
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 全量裁剪 + 资源收缩。
            //
            // 【为什么以前是 false】原判断是"Xposed 入口按类名找，混淆会弄丢" —— 这是误判：
            // 按类名加载的入口只有两个（HookEntry / XposedProvider），都在
            // app/proguard-rules.pro 里显式 keep 住了。R8 剪掉的是**没被引用到的库代码**：
            // 本次打包前 dex 里有 17,803 个类，工程自己的不到 300 个，其余全是
            // androidx.compose.*（text / lazy / gestures / animation…）和 miuix 的 316 个
            // basic 组件 —— 一行都没执行过。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("未找到 keystore.properties —— release 将使用 debug 签名")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // dex 压缩进 APK（minSdk >= 28 时 AGP 默认是不压缩、安装时直接 mmap，
        // 体积会大 20MB 左右）。这里要的是包小，所以打开 legacy 打包。
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libxposed API 102（由 io.github.libxposed:api:102.0.0 的 AAR 解出的 classes.jar）。
    // 只参与编译，运行时由框架提供，绝不打包进 APK。
    compileOnly(files("libs/libxposed-api-102.jar"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    // 玻璃底：miuix 官方的 miuix-blur（AGSL 手写可分离高斯 + 自适应降采样）。
    implementation(libs.miuix.blur)
    // 0.9.5 新增模块：下拉 / 弹窗 / 顶栏的 OS4 玻璃组件，以及它们依赖的材质与描边。
    // 三件同版本、由 miuix-glass 以 api 传递；这里显式声明是为了让 EdgeStroke 等
    // 直接 import 有稳定可见性。
    implementation(libs.miuix.glass)
    implementation(libs.miuix.material)
    // 空间壁纸的深度推理（Depth Anything V2 ViT-S int8）。
    // **完整版**：mobile（minimal build）缺 ConvInteger 等算子，实测跑不动这个模型，
    // 2026-10-22 用户决定退回完整版（so 17.8MB），模型直接用 `.onnx`。
    implementation(libs.onnxruntime.android)
}
