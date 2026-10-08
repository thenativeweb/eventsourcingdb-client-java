plugins {
    id("eventsourcingdb.java-conventions")
}

description = "Spring Boot support for the official Java client SDK for EventSourcingDB."

// Names the module after its root package for applications that use the Java
// module system. Without a name, Java derives one from the file name of the
// jar, which changes when the file is renamed. The module can not declare
// itself in a module-info, since some modules it requires, e.g. those of
// Spring Boot, do not declare themselves either, and javac warns about
// requiring them.
tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "io.thenativeweb.eventsourcingdb.springboot")
    }
}

dependencies {
    api(project(":eventsourcingdb"))
    api(libs.spring.boot.starter)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // The support for @ServiceConnection needs the test container and the
    // Testcontainers support of Spring Boot. Applications only have them in
    // their tests, where they add them anyway to create the container, so the
    // starter does not bring them along, and only uses them when they are
    // present.
    compileOnly(project(":eventsourcingdb-testcontainers"))
    compileOnly(libs.spring.boot.testcontainers)

    // The test support of Spring Boot builds on AssertJ, which it only depends
    // on optionally.
    testImplementation(libs.assertj.core)
    testImplementation(libs.spring.boot.jackson)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(project(":eventsourcingdb-testcontainers"))
}

// Spring Boot logs with Logback, which the starter brings along, and refuses
// to start next to another SLF4J provider, such as the one that silences
// Testcontainers in the tests of the other modules. Here, Logback does that,
// configured in logback-test.xml.
configurations.testRuntimeClasspath {
    exclude(group = "org.slf4j", module = "slf4j-nop")
}

// The configuration processor of Spring Boot reads @ConfigurationProperties
// without claiming it, as do all annotation processors that only collect
// information. javac warns about every annotation no processor claims, which
// fails the build. That warning says nothing about the code, so it is turned
// off here, and here only; all other warnings stay errors.
tasks.compileJava {
    options.compilerArgs.add("-Xlint:-processing")
}
