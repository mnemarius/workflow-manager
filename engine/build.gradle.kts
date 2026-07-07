plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// Spring Boot 3.4 manages Testcontainers 1.20.x, whose bundled docker-java rejects
// Docker Engine 29+ (API >= 1.44) with HTTP 400. Bump to a 1.x that negotiates correctly.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":protocol"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jooq")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation(libs.grpc.services)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.json.schema.validator)

    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.logstash.logback.encoder)

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
