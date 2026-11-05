// Vendored copy of OsmAnd's public AIDL API (OsmAnd-api module, GPLv3).
// Upstream commit: see UPSTREAM_COMMIT. Do not edit the sources: the parcel
// layout must match what the installed OsmAnd expects.
plugins {
    id("com.android.library")
}

android {
    namespace = "net.osmand.aidlapi"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    sourceSets["main"].apply {
        aidl.srcDirs("src")
        java.srcDirs("src")
    }
    buildFeatures {
        aidl = true
        buildConfig = false
    }
    lint {
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.6.0")
}
