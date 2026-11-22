plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// SMS sign-in needs google-services.json from your Firebase project (see android/README.md).
// Without it the app still builds, so CI can compile it and dev sign-in still works.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = "com.google.gms.google-services")
}

val apiUrl: String = providers.gradleProperty("foundwith.apiUrl").getOrElse("http://10.0.2.2:8080").trimEnd('/')
val devSignIn: Boolean = providers.gradleProperty("foundwith.devSignIn").getOrElse("false").toBoolean()
// Every upload to Google Play needs a higher versionCode. CI passes its run number.
val appVersionCode: Int = providers.gradleProperty("foundwith.versionCode").getOrElse("1").toInt()
val appVersionName: String = providers.gradleProperty("foundwith.versionName").getOrElse("1.0.0")
// Google Play requires a privacy policy link inside the app as well as on the store listing.
// The public address people see (Firebase Hosting in front of the API): /privacy and /delete live there.
val publicUrl: String = providers.gradleProperty("foundwith.publicUrl").getOrElse("").trim().trimEnd('/')
val privacyUrl: String = providers.gradleProperty("foundwith.privacyUrl")
    .getOrElse(if (publicUrl.startsWith("https://")) "$publicUrl/privacy" else "").trim()
// The project's shared debug key (CI passes it), so every test APK has the fingerprint registered in
// Firebase for SMS sign-in. Without it, debug builds use this computer's own debug key.
val sharedDebugKeystore: String? = providers.gradleProperty("foundwith.debugKeystore").orNull
// The Play upload key, for release bundles: -Pwhohas.uploadKeystore=PATH -Pwhohas.uploadPassword=...
val uploadKeystore: String? = providers.gradleProperty("whohas.uploadKeystore").orNull
val uploadPassword: String = providers.gradleProperty("whohas.uploadPassword").getOrElse("")

// A release build pointed at the emulator's laptop address (or any plain-http URL) would be a
// broken app on every phone: refuse to build it.
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.project == project && it.name.contains("Release") }
    if (buildsRelease && !apiUrl.startsWith("https://")) {
        throw GradleException("Release builds need foundwith.apiUrl set to your https Cloud Run URL (now: $apiUrl).")
    }
    if (buildsRelease && !privacyUrl.startsWith("https://")) {
        throw GradleException("Release builds need foundwith.privacyUrl set to your privacy policy's https address.")
    }
}

android {
    namespace = "app.foundwith"
    compileSdk = 36

    // The app is in English only: leave out the translations that libraries bring along.
    androidResources {
        localeFilters += listOf("en")
    }

    defaultConfig {
        // The Play Store ID (permanent once uploaded). The code keeps its app.foundwith package names.
        applicationId = "app.whohas"
        // Runs on Android 8+.
        minSdk = 26
        // Google Play requires API 36 for new apps and updates from 31 Aug 2026.
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
        buildConfigField("String", "PRIVACY_URL", "\"$privacyUrl\"")
        buildConfigField("String", "PUBLIC_URL", "\"$publicUrl\"")
        // The consent text in the app is written for this version of the server's terms.
        buildConfigField("String", "CONSENT_VERSION", "\"2026-11-16\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
        buildConfigField("boolean", "DEV_SIGN_IN", "false")
        // The one number Google Play's reviewers sign in with: a Firebase test number whose code
        // is typed by hand. Every other number must have its SMS read on this phone.
        buildConfigField("String", "REVIEW_NUMBER", "\"${(project.findProperty("whohas.reviewNumber") as String?) ?: ""}\"")
    }

    // "full" reads the call log (the Calls tab and after-call notifications); "contactsOnly" doesn't
    // ask for call-log access at all, for the first Play Store release, before Google approves the
    // call-log Permissions Declaration.
    flavorDimensions += "permissions"
    productFlavors {
        create("full") {
            dimension = "permissions"
            buildConfigField("boolean", "CALL_LOG", "true")
        }
        create("contactsOnly") {
            dimension = "permissions"
            buildConfigField("boolean", "CALL_LOG", "false")
        }
    }

    signingConfigs {
        // The Play upload key (.github/workflows/release.yml reads it from Secret Manager). Google Play
        // re-signs the uploaded bundle with its own app signing key.
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = uploadPassword
                keyAlias = "upload"
                keyPassword = uploadPassword
            }
        }
        getByName("debug") {
            if (sharedDebugKeystore != null) {
                storeFile = file(sharedDebugKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "DEV_SIGN_IN", devSignIn.toString())
        }
        release {
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
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
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the app's resources (fonts, icons, themes) to draw screens and notifications.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.auth)
    implementation(libs.play.services.auth.api.phone)
    // Which invite link the app was installed from (Google Play Install Referrer).
    implementation(libs.installreferrer)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
