plugins {
    id("eventsourcingdb.java-conventions")
}

description = "Testcontainers support for the official Java client SDK for EventSourcingDB."

dependencies {
    api(project(":eventsourcingdb"))
    api(libs.testcontainers)
}
