import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    jacoco
    id("net.ltgt.errorprone")
    id("com.diffplug.spotless")
    id("com.vanniktech.maven.publish")
}

val libs = the<VersionCatalogsExtension>().named("libs")

group = "io.thenativeweb"

// The release workflow sets the version with -Pversion.
if (version == Project.DEFAULT_VERSION) {
    version = "0.0.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// Every public type and member needs a Javadoc comment. The compiler checks
// that, so a missing comment fails the build like any other warning.
tasks.compileJava {
    options.compilerArgs.add("-Xdoclint:all/protected")
}

// NullAway checks the code against its JSpecify annotations: in a package
// marked @NullMarked, everything is non-null unless it says @Nullable. Only
// NullAway runs; the other checks of Error Prone stay off. Tests pass null on
// purpose now and then, so they are not checked.
tasks.withType<JavaCompile>().configureEach {
    options.errorprone {
        disableAllChecks = true
        check("NullAway", CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.compileTestJava {
    options.errorprone.enabled = false
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

dependencies {
    api(libs.findLibrary("jspecify").get())
    errorprone(libs.findLibrary("errorprone-core").get())
    errorprone(libs.findLibrary("nullaway").get())

    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.findLibrary("slf4j-nop").get())
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = libs.findVersion("jacoco").get().requiredVersion
}

tasks.jacocoTestReport {
    reports {
        html.required = true
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            listOf("INSTRUCTION", "BRANCH", "LINE", "METHOD", "CLASS").forEach { name ->
                limit {
                    counter = name
                    minimum = "1.0".toBigDecimal()
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, tasks.javadoc)
}

spotless {
    java {
        palantirJavaFormat(libs.findVersion("palantir-java-format").get().requiredVersion)
        removeUnusedImports()
        forbidWildcardImports()
    }
}

mavenPublishing {
    publishToMavenCentral()

    // Signing needs the key from the release workflow, so local builds,
    // e.g. publishToMavenLocal, stay unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates(group.toString(), project.name, version.toString())

    pom {
        name = project.name
        description = provider { project.description }
        url = "https://github.com/thenativeweb/eventsourcingdb-client-java"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/license/mit"
            }
        }
        developers {
            developer {
                id = "thenativeweb"
                name = "the native web GmbH"
                email = "hello@thenativeweb.io"
                url = "https://www.thenativeweb.io"
            }
        }
        scm {
            url = "https://github.com/thenativeweb/eventsourcingdb-client-java"
            connection = "scm:git:https://github.com/thenativeweb/eventsourcingdb-client-java.git"
            developerConnection = "scm:git:ssh://git@github.com/thenativeweb/eventsourcingdb-client-java.git"
        }
    }
}
