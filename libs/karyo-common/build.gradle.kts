plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.2.0")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
    compileOnly("jakarta.validation:jakarta.validation-api:3.1.0")
    compileOnly("org.hibernate.orm:hibernate-core:6.6.13.Final")

    // Panache common types (Sort, Page) for pagination utilities
    compileOnly("io.quarkus:quarkus-panache-common:3.25.3")

    // Patchable<T> tri-state Jackson deserializer (com.karyo.common.patch). Compile-only:
    // karyo-app supplies the runtime jars. These declarations are not runtime pins;
    // platform selection and the root build's security floors determine resolved versions.
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.19.2")
    compileOnly("com.fasterxml.jackson.core:jackson-core:2.19.2")
}
