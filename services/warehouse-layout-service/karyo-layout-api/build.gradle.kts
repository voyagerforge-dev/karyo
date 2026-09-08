plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // Minimal deps — this is the published contract
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.jakarta.persistence.api)
    compileOnly(libs.jakarta.validation.api)

    // @get:JsonProperty on LocationResponse.xPos/yPos/zPos (pins the wire name against Jackson's
    // getter-name mangling -- jackson-annotations only, no databind needed here; same pattern as
    // karyo-product-api's UpdateProductRequest).
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.19.2")
}
