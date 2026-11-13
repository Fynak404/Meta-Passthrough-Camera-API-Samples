plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.meta.spatial.plugin") version "0.15.0" apply false
}

allprojects {
    configurations.all {
        resolutionStrategy {
            // Forceer dat alle Kotlin-bibliotheken binnen de dependencies 
            // netjes worden gelijkgetrokken en dwing af dat externe metrics worden gedropt
            eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion("2.1.0")
                }
            }
            // Blokkeer en vervang de corrupte metrics-container direct op classpath-niveau
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler")
        }
    }
}
