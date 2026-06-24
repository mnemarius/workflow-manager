rootProject.name = "workflow-manager"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("engine", "worker-sdk")
