// Top-level build file
plugins {
    // androidx.leanback 1.2.0 requires AGP 8.6.0+ (its AAR metadata declares this
    // as a hard minimum). AGP 8.6.x's own minimum required Gradle version is
    // still 8.7, so this stays compatible with the pinned Gradle 8.7 in CI.
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
