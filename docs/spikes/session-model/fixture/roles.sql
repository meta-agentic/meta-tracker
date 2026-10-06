-- Tessera's three-role model (copied from its persistence test fixture): neither Flyway
-- nor the runtime connects as a superuser, so FORCE ROW LEVEL SECURITY binds both.
CREATE ROLE iam_migrator LOGIN PASSWORD 'iam_migrator' NOSUPERUSER;
CREATE ROLE iam_app LOGIN PASSWORD 'iam_app' NOSUPERUSER;
GRANT CREATE, USAGE ON SCHEMA public TO iam_migrator;
