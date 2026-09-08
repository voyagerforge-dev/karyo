# Third-party notices

Karyo WMS distributions include third-party components under their own
licenses. This inventory was established on 2026-08-02 and is maintained against
the public dependency manifests in the Karyo source repository: the Gradle
version catalog, and the web and mobile lockfiles.

| Component | License | Notes |
|---|---|---|
| com.openhtmltopdf:openhtmltopdf-pdfbox (+ deps) | **LGPL-2.1** | PDF rendering. Shipped as a separate jar (Quarkus fast-jar `lib/`), replaceable by the recipient. **Build rule: never shade/fat-jar this artifact into Karyo code.** License text: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt |
| Jakarta EE APIs (ws.rs, persistence, cdi, validation, inject) | EPL-2.0 (some dual GPL-2.0+Classpath-Exception / EDL-1.0) | API jars via Quarkus |
| Hibernate ORM (runtime selected by the Quarkus BOM) | Apache-2.0 | See [upstream licensing](https://hibernate.org/community/license/) for the boundary between current and earlier releases. |
| Quarkus, Quarkiverse extensions, Jackson, Kotlin stdlib, SLF4J (MIT), REST-assured, MockK, AssertJ | Apache-2.0 / MIT | permissive |
| JUnit Jupiter / JUnit Platform | EPL-2.0 | test-scope only, not distributed |
| @fontsource-variable/* font binaries (JetBrains Mono, Space Grotesk, Geist, Geist Mono) | **SIL OFL-1.1** | bundled in the web build; reserved-font-name terms apply. https://openfontlicense.org |
| React, Vite, shadcn/ui, TanStack Query, Playwright, and other npm dependencies | MIT / ISC / Apache-2.0 | per package.json |
| Gatling + gatling-charts-highcharts | Apache-2.0 + Gatling Highcharts License (proprietary) | **development tooling only** (`tests/gatling`) — never part of any distributed artifact |
| Gradle wrapper (`gradlew`, `gradlew.bat`) | Apache-2.0 | build tooling |
