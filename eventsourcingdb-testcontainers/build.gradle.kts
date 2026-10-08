plugins {
    id("eventsourcingdb.java-conventions")
}

description = "Testcontainers support for the official Java client SDK for EventSourcingDB."

// Names the module after its root package for applications that use the Java
// module system. Without a name, Java derives one from the file name of the
// jar, which changes when the file is renamed.
tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "io.thenativeweb.eventsourcingdb.testcontainers")
    }
}

dependencies {
    api(project(":eventsourcingdb"))
    api(libs.testcontainers)
}
