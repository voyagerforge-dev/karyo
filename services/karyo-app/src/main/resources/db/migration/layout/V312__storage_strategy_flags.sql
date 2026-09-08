-- L6: myWMS StorageStrategy flags — manualSearch/onlyClientLocation enforced by this
-- task's finder wiring; useAreaStrategyDate/useItemDataArea are columns only here
-- (Task 3 wires their finder behavior against the L1 StorageArea trio).
ALTER TABLE karyo.storage_strategies
    ADD COLUMN only_client_location BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN manual_search BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN use_area_strategy_date BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN use_item_data_area BOOLEAN NOT NULL DEFAULT FALSE;
