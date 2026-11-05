import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 0.2.0 → 200, 1.4.12 → 10412 — เพิ่มเลขตัวไหนก็ได้ versionCode ใหม่จะมากกว่าเดิมเสมอ
val appVersionName = providers.gradleProperty("appVersionName").get()
val appVersionCode = appVersionName.split(".").map { it.toInt() }
    .also { require(it.size == 3 && it.all { n -> n in 0..99 } ) { "appVersionName ต้องเป็น X.Y.Z (แต่ละตัว 0-99)" } }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// กุญแจเซ็นตัวจริงอยู่นอก git — ถ้าไม่มีไฟล์นี้ build release จะได้ APK ที่ยังไม่ได้เซ็น
val keystoreProps = rootProject.file("keystore/keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.mm.notifyhook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mm.notifyhook"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"${providers.gradleProperty("updateRepo").get()}\"")
    }

    signingConfigs {
        keystoreProps?.let { p ->
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // ตัว debug เป็นอีกแอปหนึ่ง ติดตั้งคู่กับตัวจริงได้ ไม่ชนกันเรื่องลายเซ็น
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
}

// ./gradlew :app:dist → dist/NotifyHook-v<เวอร์ชัน>.apk (ตัว release ที่เซ็นแล้ว พร้อมส่งให้ติดตั้ง)
tasks.register<Copy>("dist") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "NotifyHook-v$appVersionName.apk" }
    doFirst { check(keystoreProps != null) { "ไม่พบ keystore/keystore.properties — เซ็น APK ไม่ได้" } }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
    // android.jar มีแค่ stub ของ org.json — ใช้ตัวจริงตอนรัน unit test
    testImplementation("org.json:json:20240303")
}
