plugins {
    id("com.android.library")
}

android {
    namespace = "com.senseauto.healthdetectlib"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
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

    buildFeatures {
        aidl = true
    }
}

afterEvaluate {
    tasks.register<Jar>("makeJar") {
        archiveBaseName.set("health_aidl-1.0.0")
        archiveVersion.set("1.0.0")
        destinationDirectory.set(layout.buildDirectory.dir("outputs/jar"))

        from("$buildDir/intermediates/javac/release/classes")
        from("$buildDir/tmp/kotlin-classes/release")

        exclude("**/R.class")
        exclude("**/R$*.class")
        exclude("**/BuildConfig.class")
        exclude("META-INF/**")
    }
}

dependencies {

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation ("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}