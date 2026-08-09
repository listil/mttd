import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ksp)
}

// 릴리스 서명 정보. 저장소에 커밋하지 않는다 (.gitignore).
// keystore.properties 가 없으면 release 빌드는 서명되지 않은 채로 나온다 (CI/기여자용).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { s -> load(s) }
}

android {
    namespace = "com.mttd"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mttd"
        minSdk = 29
        targetSdk = 34
        // 릴리스마다 반드시 올릴 것. 안 올리면 시스템이 업데이트로 인식하지 않는다.
        // versionName 은 GitHub 릴리스 태그(vX.Y.Z)와 맞춘다 — 인앱 업데이트 확인이 이걸로 비교.
        versionCode = 11
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 인앱 업데이트 확인이 조회할 저장소.
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: "listil/mttd"}\"")
    }

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

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

// 산출물 파일명 — 기본값(app-release.apk)은 어떤 앱인지, 무슨 버전인지 알 수 없다.
// -> mttd-0.2.1-release.apk / mttd-0.2.1-debug.apk
// (파일명은 설치·업데이트 판단과 무관 — 안드로이드는 applicationId + 서명만 본다.)
base {
    archivesName.set("mttd-${android.defaultConfig.versionName}")
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    // Room — 회차 기록을 디스크로 내려 프로세스 메모리 증가를 막는다
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Compose (BOM 이 나머지 버전 확정)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Networking & protobuf
    implementation(libs.okhttp)
    implementation(libs.protobuf.kotlin.lite)

    // Image loading
    implementation(libs.coil.compose)
}
