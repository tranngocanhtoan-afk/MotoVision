// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.26" apply false

    // Dòng này để nhận diện Firebase
    id("com.google.gms.google-services") version "4.4.0" apply false
}

