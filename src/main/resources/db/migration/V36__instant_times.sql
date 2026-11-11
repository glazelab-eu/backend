-- The last four times stored as zone-less wall times become instants like every other time (backend guide "Sync",
-- dev guide "Cross-repository contracts"): combination creation, photo upload, photo report and photo deletion log.
--
-- ZONE ASSUMPTION: the rows were written as wall times of the API JVM's default zone (LocalDateTime.now()). In
-- production that is UTC: compose.yaml sets no TZ for the api service and the eclipse-temurin jammy image runs in UTC.
-- SET DATA TYPE reads each old value in the migrating session's zone, which the PostgreSQL driver and embedded H2 take
-- from the same JVM default zone, so the conversion is right in any zone as long as the API that runs this migration
-- runs in the zone that wrote the rows (never change TZ together with this release). NOT NULL and indexes stay.
ALTER TABLE combination ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE image ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE image_report ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE image_deletion ALTER COLUMN deleted_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
