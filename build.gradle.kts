// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.meta.spatial.plugin") version "0.15.0" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
