-- defect row :1443 (burndown-5): unit_loads.weight NUMERIC(16,3) vs its inputs (19,3)
-- weight holds the effective value (weight_measure when set, otherwise weight_calculated),
-- both of which are already NUMERIC(19,3) (V110). A narrower target column than either of its
-- two sources is an overflow waiting to happen once a tare + content total needs more than 13
-- integer digits.
ALTER TABLE karyo.unit_loads ALTER COLUMN weight TYPE NUMERIC(19,3);
