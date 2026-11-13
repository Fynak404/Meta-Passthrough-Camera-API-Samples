// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.
}
plugins {
    id("com.meta.spatial.plugin") version "x.y.z" // Meta Spatial SDK plugin
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
}

dependencies {
    implementation("com.meta.spatial:spatial-sdk:x.y.z")
    implementation("com.meta.spatial:spatial-sdk-mruk:x.y.z") // Mixed Reality Utility Kit for Room Scanning
}
