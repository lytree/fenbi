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
        versionCode = versionCodeOverride ?: 3
        versionName = versionNameOverride ?: "0.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
