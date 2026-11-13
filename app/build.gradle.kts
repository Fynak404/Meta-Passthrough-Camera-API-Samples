dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Meta Spatial SDK & MRUK Room Understanding (met exclusion op de corrupte compiler-embeddable)
    implementation("com.meta.spatial:meta-spatial-sdk:0.15.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler")
    }
    implementation("com.meta.spatial:meta-spatial-sdk-mruk:0.15.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler")
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
