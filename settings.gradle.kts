rootProject.name = "karyo-wms"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Shared infrastructure libraries
include(
    ":libs:karyo-common",
    ":libs:karyo-events",
    ":libs:karyo-security",
    ":libs:karyo-license",
    ":libs:karyo-documents",
    ":libs:karyo-sequence",
)

// Inventory service modules
include(
    ":services:inventory-service:karyo-inventory-api",
    ":services:inventory-service:karyo-inventory-core",
    ":services:inventory-service:karyo-inventory-ext-example",
)

// Auth service modules
include(
    ":services:auth-service:karyo-auth-api",
    ":services:auth-service:karyo-auth-core",
)

// Product service modules
include(
    ":services:product-service:karyo-product-api",
    ":services:product-service:karyo-product-core",
)

// Order service modules
include(
    ":services:order-service:karyo-orders-api",
    ":services:order-service:karyo-orders-core",
)

// Warehouse layout service modules
include(
    ":services:warehouse-layout-service:karyo-layout-api",
    ":services:warehouse-layout-service:karyo-layout-core",
)

// Task service modules
include(
    ":services:task-service:karyo-tasks-api",
    ":services:task-service:karyo-tasks-core",
)

// Fulfillment service modules
include(
    ":services:fulfillment-service:karyo-fulfillment-api",
    ":services:fulfillment-service:karyo-fulfillment-core",
)

// Replenishment service modules
include(
    ":services:replenishment-service:karyo-replenishment-api",
    ":services:replenishment-service:karyo-replenishment-core",
)

// Stocktaking service modules
include(
    ":services:stocktaking-service:karyo-stocktaking-api",
    ":services:stocktaking-service:karyo-stocktaking-core",
)

// Work service modules
include(
    ":services:work-service:karyo-work-api",
    ":services:work-service:karyo-work-core",
)

// Integration hub modules (webhooks; future: import/export, ERP adapters)
include(
    ":services:integration-hub-service:karyo-webhooks-api",
    ":services:integration-hub-service:karyo-webhooks-core",
)

// Reporting module (KPI read-model)
include(
    ":services:reporting-service:karyo-reporting-api",
    ":services:reporting-service:karyo-reporting-core",
)

// AI service modules (v1.6 copilot)
include(
    ":services:ai-service:karyo-ai-api",
    ":services:ai-service:karyo-ai-core",
)

// Monitoring service modules (v1.7a monitors/alerts)
include(
    ":services:monitoring-service:karyo-monitors-api",
)

// Forecasting service modules (v1.7b demand forecasting)
include(
    ":services:forecasting-service:karyo-forecasting-api",
)

// Slotting service modules (v1.7c slotting advisor)
include(
    ":services:slotting-service:karyo-slotting-api",
)

// Simulation service modules (v1.7d reorder what-if backtest)
include(
    ":services:simulation-service:karyo-simulation-api",
)

// Demo-data engine (demo-only, prod-disabled)
include(
    ":services:demo-service:karyo-demo",
)

// Document store service modules (docstore-templates sprint: persisted document archive).
// karyo-docstore-core is the free-tier archive. Commercial template engines are delivered
// only in licensed customer images.
include(
    ":services:document-service:karyo-docstore-api",
    ":services:document-service:karyo-docstore-core",
)

// Cross-docking engine (Advanced Fulfillment pack, paid, key advanced-fulfillment)
include(
    ":services:crossdock-service:karyo-crossdock-api",
)

// Wave-based bulk fulfillment (Advanced Fulfillment pack, paid, key advanced-fulfillment)
include(
    ":services:wave-service:karyo-wave-api",
)

// Order streaming (Advanced Fulfillment pack, paid, key advanced-fulfillment)
include(
    ":services:streaming-service:karyo-streaming-api",
)

// Modular-monolith aggregator app — assembles all cores into one Quarkus image
include(":services:karyo-app")

// The optional performance harness is outside this supported distribution.
