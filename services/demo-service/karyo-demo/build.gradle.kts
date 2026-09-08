plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // Deliberate cross-core coupling — karyo-demo is a prod-disabled leaf module that
    // reuses the domain entities + repositories to construct backdated demo data.
    implementation(project(":services:inventory-service:karyo-inventory-core"))
    // StockState (vo) lives in the api module; -core's dependency on it is `implementation`
    // scoped (not `api`), so it isn't transitively visible here — depend on it directly.
    implementation(project(":services:inventory-service:karyo-inventory-api"))
    implementation(project(":services:product-service:karyo-product-core"))
    implementation(project(":services:warehouse-layout-service:karyo-layout-core"))
    implementation(project(":services:order-service:karyo-orders-core"))
    // OrderState (vo) lives in the api module; -core's dependency on it is `implementation`
    // scoped (not `api`), so it isn't transitively visible here — depend on it directly.
    implementation(project(":services:order-service:karyo-orders-api"))
    implementation(project(":services:fulfillment-service:karyo-fulfillment-core"))
    implementation(project(":services:fulfillment-service:karyo-fulfillment-api"))
    implementation(project(":services:task-service:karyo-tasks-core"))
    implementation(project(":services:task-service:karyo-tasks-api"))
    implementation(project(":services:stocktaking-service:karyo-stocktaking-core"))
    // CountOrderState/CountLineState/CountSessionState/CountType (vo) live in the api module;
    // -core's dependency on it is `implementation` scoped, so it isn't transitively visible here.
    implementation(project(":services:stocktaking-service:karyo-stocktaking-api"))
    implementation(project(":services:reporting-service:karyo-reporting-core"))
    implementation(project(":services:reporting-service:karyo-reporting-api"))

    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-security"))

    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.hibernate.orm.panache.kotlin)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.jackson.module.kotlin)
    // reset()'s TRUNCATE ... RESTART IDENTITY needs to evict the Caffeine L1 caches keyed
    // on the ids it recycles (see reset() KDoc) -- @CacheInvalidateAll annotations.
    implementation(libs.quarkus.cache)
}
