plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.applock.impl"
    resourcePrefix = "applock_"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(projects.feature.applock.api)

    implementation(projects.core.outcome)
    implementation(projects.core.common)
    implementation(projects.core.ui.compose.common)
    implementation(projects.core.ui.theme.api)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose.viewmodel)

    testImplementation(projects.core.testing)
    testImplementation(projects.core.android.testing)
    testImplementation(projects.core.ui.compose.testing)
    testImplementation(projects.core.ui.legacy.theme2.k9mail)
    testImplementation(libs.androidx.test.core)
}
