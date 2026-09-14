plugins {
    id(ThunderbirdPlugins.Library.kmp)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.applock.api"
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.tb.mobile.components.core.outcome)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
