plugins {
    id("eventsourcingdb.java-conventions")
}

description = "The official Java client SDK for EventSourcingDB."

dependencies {
    api(libs.jackson.databind)

    testImplementation(project(":eventsourcingdb-testcontainers"))
}
