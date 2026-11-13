plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.meta.spatial.plugin") version "0.15.0" apply false
}

allprojects {
    configurations.all {
        // Blokkeer en vernietig de corrupte compiler-embeddable van Meta project-breed
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler")
        
        resolutionStrategy {
            eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion("2.1.0")
                }
            }
        }
    }
}
