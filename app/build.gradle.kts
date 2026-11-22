import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 签名信息只从 root/keystore.properties 读（该文件与 keystore 都已 gitignore），缺失时退回默认签名
val keystoreProps = Properties().apply {
    val text = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties")).asText.orNull
    if (text != null) load(text.reader())
}

android {
    namespace = "me.riverfrozer.penguinsmagic"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "me.riverfrozer.penguinsmagic"
        minSdk = 29
        targetSdk = 37
        versionCode = 10102
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
