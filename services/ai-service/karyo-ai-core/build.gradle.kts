plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    implementation(project(":services:ai-service:karyo-ai-api"))
    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-security"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-sequence"))   // SequenceNumberService (SC17 generation-site rollout)
    // Domain module deps — tool beans call these services in-process
    implementation(project(":services:product-service:karyo-product-api"))
    implementation(project(":services:product-service:karyo-product-core"))
    implementation(project(":services:inventory-service:karyo-inventory-api"))
    implementation(project(":services:inventory-service:karyo-inventory-core"))
    implementation(project(":services:warehouse-layout-service:karyo-layout-api"))
    implementation(project(":services:warehouse-layout-service:karyo-layout-core"))
    implementation(project(":services:reporting-service:karyo-reporting-api"))
    implementation(project(":services:reporting-service:karyo-reporting-core"))
    // T5: read tools batch 2 — orders, fulfillment, webhooks, work, replenishment
    implementation(project(":services:order-service:karyo-orders-api"))
    implementation(project(":services:order-service:karyo-orders-core"))
    implementation(project(":services:fulfillment-service:karyo-fulfillment-api"))
    implementation(project(":services:fulfillment-service:karyo-fulfillment-core"))
    implementation(project(":services:integration-hub-service:karyo-webhooks-api"))
    implementation(project(":services:integration-hub-service:karyo-webhooks-core"))
    implementation(project(":services:work-service:karyo-work-api"))
    implementation(project(":services:work-service:karyo-work-core"))
    implementation(project(":services:replenishment-service:karyo-replenishment-api"))
    implementation(project(":services:replenishment-service:karyo-replenishment-core"))
    implementation(project(":services:stocktaking-service:karyo-stocktaking-api"))
    implementation(project(":services:stocktaking-service:karyo-stocktaking-core"))
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(enforcedPlatform(libs.quarkus.langchain4j.bom))
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.jackson.module.kotlin)
    // Panache on classpath so Kotlin resolves PanacheRepository supertypes of injected repos
    implementation(libs.quarkus.hibernate.orm.panache.kotlin)
    implementation(libs.quarkus.langchain4j)
    implementation(libs.quarkus.langchain4j.anthropic)
    implementation(libs.quarkus.langchain4j.ollama)
}
