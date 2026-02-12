plugins {
    id(ThunderbirdPlugins.Library.kmp)
}

kotlin {
    androidLibrary {
        namespace = "net.thunderbird.feature.applock.api"
        withHostTest {}
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.core.outcome)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
