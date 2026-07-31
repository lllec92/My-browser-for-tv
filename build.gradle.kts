// Top-level build file
plugins {
    // AGP 8.5.2's minimum required Gradle version is 8.7, which matches the
    // Gradle version used in CI. (Newer AGP releases such as 8.7.x/8.9.x raise
    // the minimum Gradle requirement to 8.9+, which caused a build failure
    // when the runner's Gradle was 8.7 — pinning here avoids that mismatch.)
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
