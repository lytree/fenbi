import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号默认值；CI 里 tag 构建用 -PversionName=x.y.z -PversionCode=n 覆盖
val versionNameOverride = providers.gradleProperty("versionName").orNull?.removePrefix("v")
val versionCodeOverride = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

// release 签名：优先 keystore.properties（本地），其次环境变量（CI 密钥）
// 两者都没有时 release 退回 debug 签名，保证 CI 产物可直接安装
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secretOrNull(envName: String, propName: String): String? =
    (System.getenv(envName) ?: keystoreProperties.getProperty(propName))?.takeIf { it.isNotBlank() }

val releaseStoreFile = secretOrNull("QBANK_KEYSTORE_FILE", "storeFile")
val hasReleaseKeystore = releaseStoreFile != null && file(releaseStoreFile).exists()

// 云备份凭证：两家网盘开放平台的应用信息（AppKey / SecretKey）。
// 与签名同一套取法——keystore.properties 或环境变量，都不进版本库。
// 留空也能用：App 内「云备份 → 应用凭证」可以手填，换密钥不必重新编译。
val baiduAppKey = secretOrNull("QBANK_BAIDU_APPKEY", "baiduAppKey") ?: ""
val baiduSecretKey = secretOrNull("QBANK_BAIDU_SECRETKEY", "baiduSecretKey") ?: ""
val tianyiAppKey = secretOrNull("QBANK_TIANYI_APPKEY", "tianyiAppKey") ?: ""
val tianyiAppSecret = secretOrNull("QBANK_TIANYI_APPSECRET", "tianyiAppSecret") ?: ""
if (!hasReleaseKeystore) {
    logger.lifecycle("未找到 release 签名密钥（keystore.properties 或 QBANK_KEYSTORE_* 环境变量），release 构建将使用 debug 签名")
}

android {
    namespace = "com.lytree.qbank"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lytree.qbank"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeOverride ?: 12
        versionName = versionNameOverride ?: "0.0.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 云端凭证编进 BuildConfig；空串表示未配置，运行时回落到 App 内手填的值
        buildConfigField("String", "BAIDU_APP_KEY", "\"$baiduAppKey\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"$baiduSecretKey\"")
        buildConfigField("String", "TIANYI_APP_KEY", "\"$tianyiAppKey\"")
        buildConfigField("String", "TIANYI_APP_SECRET", "\"$tianyiAppSecret\"")
    }

    testOptions {
        animationsDisabled = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = secretOrNull("QBANK_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = secretOrNull("QBANK_KEY_ALIAS", "keyAlias")
                keyPassword = secretOrNull("QBANK_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 云备份走 WebDAV，需要 PROPFIND / MKCOL 这类非标准方法。
    // Android 的 HttpURLConnection 方法名是硬编码白名单（只有 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE），
    // 调 PROPFIND 会直接抛 ProtocolException，所以这里破例引一个 OkHttp。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 纯文本格式（CSV / TSV / Anki / TXT）的解析逻辑不碰 Android API，用 JVM 单元测试跑最快
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
