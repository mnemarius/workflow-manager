plugins {
    `java-library`
    application
}

dependencies {
    api(project(":protocol"))
    implementation(libs.grpc.netty.shaded)

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testImplementation(libs.grpc.testing)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.workflowmanager.worker.sample.SampleWorker"
}
