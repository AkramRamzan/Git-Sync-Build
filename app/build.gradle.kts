plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace   = "com.aro.code"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.aro.code"
        minSdk          = 26
        targetSdk       = 28
        versionCode     = 1
        versionName     = "2.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Expose OAuth client IDs
        buildConfigField("String", "GITHUB_CLIENT_ID",
            "\"${project.findProperty("GITHUB_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID",
            "\"${project.findProperty("GOOGLE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET",
            "\"${project.findProperty("GOOGLE_CLIENT_SECRET") ?: ""}\"")
        buildConfigField("String", "GEMINI_MODEL_ID",
            "\"gemini-3.8-flash\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            // Populated from environment / CI secrets via gradle.properties
            storeFile   = file(project.findProperty("RELEASE_STORE_FILE")   ?: "debug.keystore")
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD")  as String? ?: ""
            keyAlias    = project.findProperty("RELEASE_KEY_ALIAS")         as String? ?: ""
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD")      as String? ?: ""
        }
    }

    buildTypes {
        debug {
            isDebuggable    = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig   = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xcontext-receivers")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/DEPENDENCIES.txt",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/eclipse.inf",
                "META-INF/ECLIPSE_.SF",
                "META-INF/ECLIPSE_.RSA",
                "META-INF/ECLIPSE_.DSA",
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA",
                "OSGI-INF/**",
                "plugin.properties",
                "about.html",
                "about_files/**",
                "ant_tasks/**",
                "NOTICE",
                "LICENSE"
            )
        }
    }
}

dependencies {
    // Core desugaring for java.nio on API < 26
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // AndroidX Core & Material
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.browser)
    implementation("com.google.android.material:material:1.12.0")

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    implementation(libs.compose.viewmodel)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.nav.compose)

    // Credential Manager / Auth
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)

    // DataStore & Security
    implementation(libs.datastore.prefs)
    implementation(libs.security.crypto)

    // Ktor Networking
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.neg)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)
    implementation(libs.kotlinx.serialization)

    // Coroutines
    implementation(libs.kotlinx.coroutines)

    // JGit
    implementation(libs.jgit.core)
    implementation(libs.jgit.http)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(platform(libs.compose.bom))
}
