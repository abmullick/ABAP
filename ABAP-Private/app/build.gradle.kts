plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.abap.privatep2p"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.abap.privatep2p"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:identity"))
    implementation(project(":core:crypto"))
    implementation(project(":core:protocol"))
    implementation(project(":core:network"))
    implementation(project(":core:storage"))
    implementation(project(":core:security"))
    implementation(project(":feature:pairing"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:calling"))
    implementation(project(":feature:connection"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":feature:settings"))
    implementation(project(":service:foreground"))
    implementation(project(":service:audio"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
