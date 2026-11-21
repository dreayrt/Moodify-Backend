"""Offline guards: these tests never connect to MySQL or MongoDB."""
from pathlib import Path

import pytest

from integration_smoke import execute_script, drop_disposable_databases, safe_schema_statements


DATABASE = "moodify_recommendation_test_a123456789ab"
TABLE = "CREATE TABLE `users` (`id` BIGINT PRIMARY KEY, name VARCHAR(100) DEFAULT 'a;b')"


class FakeConnection:
    def __init__(self, database=DATABASE):
        self.database = database
        self.statements = []
        self.commits = 0

    def cursor(self):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, statement):
        self.statements.append(statement)

    def fetchone(self):
        return {"selected_database": self.database}

    def commit(self):
        self.commits += 1


class FakeMongoClient:
    def __init__(self):
        self.dropped = []

    def drop_database(self, database):
        self.dropped.append(database)


def mutations(connection):
    return [s for s in connection.statements if not s.startswith("SELECT DATABASE()")]


def test_application_database_bootstrap_is_never_executed():
    sql = FakeConnection()
    source = """
        -- Application bootstrap must not escape the fixture connection.
        DROP DATABASE IF EXISTS moodify;
        CREATE DATABASE moodify CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
        USE moodify;
    """ + TABLE + ";"
    execute_script(sql, source, DATABASE)
    assert mutations(sql) == [TABLE]
    assert sql.commits == 1


@pytest.mark.parametrize("unsafe", [
    "DROP DATABASE another_database",
    "DROP SCHEMA moodify",
    "ALTER DATABASE moodify READ ONLY = 1",
    "DROP TABLE moodify.users",
    "CREATE TABLE moodify.users (id INT)",
    "CREATE TABLE `moodify`.`users` (id INT)",
    "CREATE TABLE users (id INT, FOREIGN KEY (id) REFERENCES moodify.users(id))",
    "CREATE TABLE users (id INT, FOREIGN KEY (id) REFERENCES `moodify`.`users`(id))",
    "CREATE TABLE users (id INT, FOREIGN KEY (id) REFERENCES outside_fixture(id))",
    "CREATE TABLE users LIKE moodify.users",
    "CREATE TABLE users AS SELECT * FROM moodify.users",
    "CREATE TABLE users (id INT) ENGINE=FEDERATED CONNECTION='mysql://remote/users'",
    "CREATE EVENT dangerous ON SCHEDULE EVERY 1 SECOND DO DELETE FROM moodify.users",
    "CREATE TRIGGER dangerous BEFORE INSERT ON users FOR EACH ROW DELETE FROM moodify.users",
    "SET GLOBAL event_scheduler=ON",
    "USE another_database",
    "/*!50000 DROP DATABASE moodify */",
    "/* prefix */ DROP DATABASE moodify",
])
def test_entire_script_is_rejected_before_any_sql_execution(unsafe):
    sql = FakeConnection()
    with pytest.raises(ValueError):
        execute_script(sql, TABLE + ";\n" + unsafe + ";", DATABASE)
    assert sql.statements == []
    assert sql.commits == 0


@pytest.mark.parametrize("selected", ["moodify", "another_database", None])
def test_schema_execution_requires_exact_selected_fixture_database(selected):
    sql = FakeConnection(selected)
    with pytest.raises(RuntimeError, match="not scoped"):
        execute_script(sql, TABLE, DATABASE)
    assert mutations(sql) == []
    assert sql.commits == 0


@pytest.mark.parametrize("database", ["moodify", "moodify_recommendation_test_", DATABASE + "_extra", "x`"])
def test_cleanup_rejects_non_disposable_names_without_touching_either_server(database):
    sql, mongo = FakeConnection(database), FakeMongoClient()
    with pytest.raises(RuntimeError, match="Invalid disposable"):
        drop_disposable_databases(sql, mongo, database)
    assert sql.statements == []
    assert mongo.dropped == []


def test_cleanup_refuses_a_connection_selected_to_the_application_database():
    sql, mongo = FakeConnection("moodify"), FakeMongoClient()
    with pytest.raises(RuntimeError, match="not scoped"):
        drop_disposable_databases(sql, mongo, DATABASE)
    assert mutations(sql) == []
    assert mongo.dropped == []


def test_cleanup_drops_only_the_exact_generated_database():
    sql, mongo = FakeConnection(), FakeMongoClient()
    drop_disposable_databases(sql, mongo, DATABASE)
    assert mutations(sql) == [f"DROP DATABASE IF EXISTS `{DATABASE}`"]
    assert mongo.dropped == [DATABASE]


def test_current_schema_is_accepted_with_albums_defined_once():
    schema = Path(__file__).resolve().parents[2] / "database/Moodify.annotated.schema.sql"
    statements = safe_schema_statements(schema.read_text(encoding="utf-8"))
    assert len(statements) == 31
    assert sum(s.startswith("CREATE TABLE albums (") for s in statements) == 1
    assert sum(s.startswith("CREATE TABLE album_tracks (") for s in statements) == 1
