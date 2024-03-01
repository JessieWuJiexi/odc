CREATE TABLE "part_hash" (
"C1" INTEGER NOT NULL
)  PARTITION BY HASH("C1")
PARTITIONS 5;
COMMENT ON TABLE "part_hash" IS 'this is a comment';

CREATE TABLE "RANGE_PARTI_TIME_TYPE" (
  "C1" DATE,
  "C2" TIMESTAMP(9),
  "C5" VARCHAR2(64)
) partition by range(c1, c2)
(partition P0 values less than (TO_DATE(' 2022-12-31 23:59:59', 'SYYYY-MM-DD HH24:MI:SS', 'NLS_CALENDAR=GREGORIAN'),Timestamp '2022-12-31 23:59:59.000000000'));

