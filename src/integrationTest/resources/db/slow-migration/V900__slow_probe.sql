-- Test-only migration, loaded by FlywayMigrationTimeoutIntegrationTest alone. It
-- stands in for the real slow case — building an index over millions of rows —
-- by taking a second, which is an order of magnitude past the tight
-- statement_timeout that test configures for the pool.
SELECT pg_sleep(1);
