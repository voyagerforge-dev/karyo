# Commercial engines

This public repository contains Karyo's Apache-2.0 source. The optional commercial
engines below are not included. Public API modules and visible UI do not install an engine,
and a licence token cannot add missing code. Execution needs both an installed commercial
engine and its entitlement. A missing REST resource in the free image is different from an
installed but unentitled commercial resource. Free warehouse workflows need neither.

Commercial delivery is designed around a licence-gated download service: the signed licence
token is exchanged for a short-lived download URL, so no registry account is required.
Confirm availability with the captain before relying on it: test-environment delivery
mechanics are demonstrated, but live R2 delivery and a customer-stack deployment are not.
The supported commercial release must supply both application and nginx image artifacts,
with their load/tag instructions. Commercial source and private build inputs are not
distributed. Third-party dependencies retain their own licences, listed in the
repository's THIRD-PARTY-NOTICES.md file.

## Event monitors

Runs six scheduled warehouse-risk detectors and delivers fired alerts through configured channels. External delivery must be configured and tested; mock SMTP is not live email. **Licence key:** `monitors`. **When unavailable:** Insights > Monitors and the dashboard exception card show locked add-on panels.

## Demand forecasting

Produces read-only, on-demand per-SKU demand forecasts and reorder suggestions. It does not place purchase orders or execute replenishment. **Licence key:** `forecasting`. **When unavailable:** Insights > Forecasting shows a locked add-on panel.

## Slotting advisor

Compares pick velocity with slot desirability and advises promotion or demotion. Read-only recommendations do not select a target bin or execute a stock move. **Licence key:** `slotting`. **When unavailable:** Insights > Slotting shows a locked add-on panel.

## Reorder simulation

Compares naive and safety-stock reorder policies against demand history on demand. This read-only backtest is not a general real-time warehouse digital twin. **Licence key:** `simulation`. **When unavailable:** Insights > Simulation shows a locked add-on panel.

## Cross-docking

Routes matching receipts to outbound staging, with ordinary putaway as the fallback. The installed engine requires entitlement and an operator-enabled matching policy. **Licence key:** `advanced-fulfillment`. **When unavailable:** There is no dedicated screen; Receiving and Tasks continue with standard putaway.

## Wave fulfillment

Selects and allocates orders for batch picking, sorting, and cross-order pack-out. This is commercial orchestration over free picking and packing primitives. **Licence key:** `advanced-fulfillment`. **When unavailable:** Fulfillment > Waves shows a locked add-on panel; floor Sort and Pack-out need the engine.

## Order streaming

Releases eligible orders in micro-batches when enabled and tracks waiting or stalled work. Operators handle stalled work; the engine does not guarantee autonomous recovery or email/Slack notification. **Licence key:** `advanced-fulfillment`. **When unavailable:** Fulfillment > Streaming shows a locked add-on panel.

## Document templates

Versions per-goods-owner overrides for generated PDF and ZPL documents. The document archive and ordinary document generation remain free. **Licence key:** `documents`. **When unavailable:** Admin > Document templates shows a locked add-on panel.

## Cartonization

Splits picked goods across cartons using configured line and quantity limits. This is not geometric or 3D packing optimization; ordinary pack-out remains free. **Licence key:** `cartonization`. **When unavailable:** The order-strategy form marks CARTONIZATION as requiring the add-on, and execution falls back to one-to-one pack-out.

## Contact the captain

For commercial access, email project captain [Yashraj Padhi](mailto:yash@karyowms.com).
