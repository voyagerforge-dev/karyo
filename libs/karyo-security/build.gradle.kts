plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
    compileOnly("io.quarkus:quarkus-security:3.25.3")
    compileOnly("org.eclipse.microprofile.jwt:microprofile-jwt-auth-api:2.1")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}
