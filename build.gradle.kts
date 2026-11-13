plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.meta.spatial.plugin") version "0.15.0" apply false
}

allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-Xskip-metadata-version-check",
                    "-Xskip-prerelease-check"
                )
            )
        }
    }
}
