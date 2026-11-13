plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.meta.spatial.plugin") version "0.15.0" apply false
}

allprojects {
    configurations.all {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion("2.1.0")
                }
            }
        }
    }
}

// Intercepteer en uitschakelen van de metrics/statistieken service die de null-pointer veroorzaakt
gradle.taskGraph.whenReady {
    allTasks.forEach { task ->
        if (task.javaClass.name.contains("BuildFlowService", ignoreCase = true) ||
            task.javaClass.name.contains("Statistics", ignoreCase = true) ||
            task.javaClass.name.contains("Metrics", ignoreCase = true)) {
            task.enabled = false
        }
    }
}
