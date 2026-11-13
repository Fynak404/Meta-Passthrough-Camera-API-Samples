plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.meta.spatial.plugin") // Required for Spatial SDK
}

dependencies {
    // Meta Spatial SDK Core & MRUK Room Understanding
    implementation("com.meta.spatial:spatial-sdk:77.0.0")
    implementation("com.meta.spatial:spatial-sdk-mruk:77.0.0")
}
