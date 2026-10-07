plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.maven.publish.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
}
