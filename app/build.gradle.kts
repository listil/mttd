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
    // direct flavor의 CMake 빌드용. NDK 26(AGP 기본값)의 bionic jni.h 는 <stdint.h>를
    // 스스로 안 끌어와서 org.lsposed.libcxx 프리팹 헤더와 조합 시 uint8_t 등이 깨졌다 —
    // Shizuku 본가가 실제 검증해서 쓰는 버전으로 고정해 같은 조합을 재현한다.
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.mttd"
        minSdk = 29
        targetSdk = 34
        // 릴리스마다 반드시 올릴 것. 안 올리면 시스템이 업데이트로 인식하지 않는다.
        // versionName 은 GitHub 릴리스 태그(vX.Y.Z)와 맞춘다 — 인앱 업데이트 확인이 이걸로 비교.
        versionCode = 14
        versionName = "0.3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 인앱 업데이트 확인이 조회할 저장소.
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: "listil/mttd"}\"")
    }

    // 두 개의 독립 APK: `shizuku`(기본, 오늘까지의 앱 그대로) / `direct`(Shizuku 없이 무선
    // adb 페어링으로 직접 붙는 fallback — 일부 기기에서 Shizuku.bindUserService() 가
    // 영원히 응답 안 하는 알려진 업스트림 버그(RikkaApps/Shizuku#475, #451) 우회용).
    // 코드 공유 원칙: main 은 두 flavor가 100% 동일하게 필요로 하는 것만 담고, 특권
    // 파일 접근 계층(및 그걸 그리는 화면 껍데기)만 src/shizuku, src/direct 로 분리한다.
    flavorDimensions += "access"
    productFlavors {
        create("shizuku") {
            dimension = "access"
        }
        create("direct") {
            dimension = "access"
            applicationIdSuffix = ".direct"
            // 무선 디버깅 자체가 Android 11+ 기능이라 이 flavor만 minSdk를 올린다.
            minSdk = 30
        }
    }

    // SPAKE2 페어링 핸드셰이크(1회성)만 native — RikkaApps/Shizuku 의 adb_pairing.cpp 포팅.
    // AGP 제약상 cmake path는 flavor별로 못 나뉘어(전역 1개) shizuku 빌드도 NDK/CMake 설정
    // 단계는 거치지만, PairingContext/AdbPairingClient 는 src/direct/kotlin 에만 있어서
    // System.loadLibrary("adb") 호출 자체가 shizuku 변형 클래스패스엔 존재하지 않는다 —
    // 결과 .so 가 shizuku APK 에 같이 담기더라도 로드되지 않는 죽은 바이트일 뿐이다.
    externalNativeBuild {
        cmake {
            path = file("src/direct/jni/CMakeLists.txt")
        }
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
        // direct flavor의 CMake 빌드가 boringssl/libcxx AAR(prefab 패키지)을 find_package()로
        // 찾으려면 필요. shizuku flavor 도 이 플래그 자체는 켜지지만 prefab 패키지 의존성이
        // 없으므로 아무 영향 없다.
        prefab = true
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
            // direct flavor: bcpkix/bcutil/bcprov 전이 의존성 버전이 서로 살짝 달라(1.80 vs
            // 1.80.2) 같은 OSGi 매니페스트 경로가 중복으로 잡힌다. 코드 동작과 무관한 리소스.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
        getByName("shizuku") {
            java.srcDirs("src/shizuku/kotlin")
        }
        getByName("direct") {
            java.srcDirs("src/direct/kotlin")
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

    // 단위 테스트 — SessionAggregator 는 순수 Kotlin(Android 의존성 없음)이라 JVM 테스트로 충분.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // direct flavor 전용 — 무선 adb 페어링/연결 (RikkaApps/Shizuku 포팅, THIRD_PARTY_NOTICES.md 참고).
    // org.lsposed.libcxx 는 의도적으로 안 씀 — CMakeLists.txt 주석 참고.
    "directImplementation"("org.bouncycastle:bcpkix-jdk18on:1.80")
    // boringssl 은 (아쉽지만) shizuku flavor 에도 필요 — externalNativeBuild.cmake.path 가
    // AGP 제약상 flavor별로 못 나뉘어 shizuku 빌드도 CMake find_package(boringssl) 를 거치기
    // 때문. shizuku 변형은 이 .so 를 로드하는 Kotlin 코드가 아예 없어 죽은 바이트로만 남는다.
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    // TLS exporter(RFC 5705) 공개 API 용 — AdbKey.sslContext 주석 참고.
    "directImplementation"("org.conscrypt:conscrypt-android:2.5.2")
}
