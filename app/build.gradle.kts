plugins {
    id("com.android.application")
}

android {
    namespace = "com.senseauto.healthdetectclient"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.senseauto.healthdetectclient"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
//    implementation(project(":healthdetectlib"))
//    implementation(project(":healthdetectlistenerlib"))
//    implementation(files("libs/healthdetect_aidl-1.3.0.jar"))
    implementation(files("libs/healthdetectlib-release2.aar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
