plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    implementation(project(":libs:karyo-common"))
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.2.0")
    compileOnly("org.hibernate.orm:hibernate-core:6.6.13.Final")
    compileOnly("io.quarkus:quarkus-hibernate-orm-panache-kotlin:3.25.3")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.18.3")
}
