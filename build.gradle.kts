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

// Zorg ervoor dat subprojecten/modules correct worden opgebouwd
subprojects {
    afterEvaluate { project ->
        if (project.hasProperty("android")) {
            project.extensions.configure("android") { ext ->
                val androidExt = ext as com.android.build.gradle.BaseExtension
                if (project.name == "app") {
                    androidExt.sourceSets {
                        getByName("main") {
                            manifest.srcFile("src/main/AndroidManifest.xml")
                            java.srcDirs("src/main/java")
                            res.srcDirs("src/main/res")
                        }
                    }
                }
            }
        }
    }
}
