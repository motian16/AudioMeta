import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 打包脚本会在根目录生成 keystore.properties；没有就退回 debug 签名，
// 这样 assembleRelease 在任何环境下都能出包，不会因为缺签名而卡住。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.audiometa.app"
    compileSdk = 34
    // 本机只装了 35/36 的 build-tools，显式指定避免 AGP 去找缺失的 34.0.0。
    buildToolsVersion = "35.0.0"

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        /*
         * 只保留真实设备用得到的 ABI。
         *
         * 唯一的原生库是 quickjs（插件运行时），它按 ABI 各打一份：
         * arm64 0.81MB、armv7 0.51MB、x86 0.84MB、x86_64 0.89MB。
         * x86/x86_64 只在模拟器上有意义，而模拟器跑不了这个 App
         * （需要真实媒体库、存储权限和大量本地文件），所以直接不发 ——
         * 省下约 1.7MB。
         */
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        applicationId = "com.audiometa.app"
        minSdk = 26
        targetSdk = 34
        /*
         * 版本号：用户指定这一版是 1.2。
         *
         * versionCode 必须单调递增（系统只认它判断"是不是升级"），
         * 所以每出一个包都往上加：1.0.0 是 3，1.1 第一版是 4，1.1 修完那批问题后是 5，
         * 1.2 是 6，订阅改按 md5 先比是 7，引导页重做是 8，
         * 修 SettingsViewModel 初始化顺序 NPE 是 9，移除曲库点播是 10，
         * 1.3（插件协议 v2：封面/完整歌词、后台保活）是 11，
         * 1.4（批量刮削按艺术家拆分、三份订阅资源、Lyrico 兼容）是 12，
         * 1.5（三基项严格匹配、规则源优先级、插件页统一）是 13。
         * versionName 是给人看的。
         */
        versionCode = 13
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // 发行版把用不到的代码和资源裁掉（用户要求"打包进去的都是用得到的"）。
            // 靠反射工作的库在 proguard-rules.pro 里整个保住了。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            /*
             * 签名方案：v2 + v3 + v4（用户要求）。
             *
             * - v2：整包签名，Android 7+；
             * - v3：带签名者身份信息、支持密钥轮换，Android 9+；
             * - v4：.idsig 增量校验，Android 11+ 安装时校验更快。
             * v1（JAR 签名）关掉：minSdk 26，目标设备全支持 v2，
             * v1 只会让包更大、签名更慢。
             */
            signingConfig = if (keystoreProps.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }.apply {
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            /*
             * OkHttp 自带的 Public Suffix List（约 40KB 压缩数据）。
             *
             * 它只在做"cookie 的域是否属于公共后缀"这种判断时才用得到，
             * 而本项目的网络请求只有：插件仓库的 GitHub 直链/镜像、
             * 刮削源、以及遥控用的 ws://IP:端口 —— 没有一个涉及
             * 跨子域 cookie。去掉它能省一点体积，且没有功能损失。
             */
            excludes += "okhttp3/internal/publicsuffix/publicsuffixes.gz"
        }
        /*
         * 只打真实设备用得到的 ABI（见 defaultConfig.ndk.abiFilters）。
         * 这里不再额外做 jniLibs 处理：release 本来就会剥符号。
         */
    }
}

// 本项目只出 APK：AAB（bundle）任务直接停用，避免误打包出 .aab。
// 只在任务真的要开始执行的那一刻停用，避免影响 AGP 内部那些名字里也带 bundle
// 的编译辅助任务（例如 bundleReleaseClassesToCompileJar）。
tasks.configureEach {
    if (name == "bundleRelease" || name == "bundleDebug") {
        doFirst {
            throw GradleException("本项目只打包 APK，AAB 任务已停用。请改用 assembleRelease。")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    // 纯逻辑单元测试跑在 JVM 上，不需要设备。
    testImplementation("junit:junit:4.13.2")
    // Android 自带的 org.json 在 JVM 测试里是空桩（调用即 "not mocked"）。
    // 测试里要解析插件返回的真实 JSON，所以换成官方实现。
    testImplementation("org.json:json:20240303")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // 拖动排序：和 Lyrico 示例客户端同一个库。
    // 但 Lyrico 用 Compose BOM 2026.09.00，我这边是 2024.06.00（compose 1.6.8），
    // 直接用 3.1.0 会因运行时找不到新 API 而闪退。2.3.3 依赖 compose 1.6.7，正好匹配。
    implementation("sh.calvin.reorderable:reorderable:2.3.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("app.cash.quickjs:quickjs-android:0.9.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
