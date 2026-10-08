plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.errorprone.gradle.plugin)
    implementation(libs.maven.publish.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
}
