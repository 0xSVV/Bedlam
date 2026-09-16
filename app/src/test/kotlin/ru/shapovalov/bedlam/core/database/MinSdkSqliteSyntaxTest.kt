package ru.shapovalov.bedlam.core.database

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class MinSdkSqliteSyntaxTest {

    private val mainSourceSet = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun `every annotated query in the app sources is a string literal`() {
        val sites = sqlSites()

        assertTrue(sites.any { it.sql != null })
        assertEquals(emptyList<String>(), sites.filter { it.requiresLiteral && it.sql == null }.map { it.location })
    }

    @Test
    fun `sql in the app sources parses on the sqlite that api 29 ships`() {
        val violations = sqlSites().flatMap { site ->
            newerSyntax(site.sql ?: return@flatMap emptyList()).map { "${site.location} uses $it" }
        }

        assertEquals(emptyList<String>(), violations)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "@Query(\"SELECT 1\")",
            "@androidx.room.Query(value = \"SELECT \" +\n    \"1\")",
            "@Query(\n    \"\"\"\n    SELECT 1\n    \"\"\",\n)",
            "@DatabaseView(\"SELECT 1\", viewName = \"one\")",
            "db.execSQL(\"SELECT 1\")",
            "db.execPerConnectionSQL(sql = \"SELECT 1\")",
            "connection.usePrepared(\"SELECT 1\") { it.step() }",
            "db.query(\"SELECT 1\")",
            "db.rawQuery(\"SELECT 1\", null)",
            "SimpleSQLiteQuery(query = \"SELECT 1\")",
        ],
    )
    fun `the guard reads the sql of every kind of site`(source: String) {
        assertEquals(listOf("SELECT 1"), sqlSites(source).map { it.sql?.trim() })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "@Query(\"SELECT '\\\$.a'\")",
            "@Query(\"\"\"SELECT '\$.a'\"\"\")",
        ],
    )
    fun `a dollar sign outside a template stays in the literal`(source: String) {
        assertEquals(listOf("SELECT '\$.a'"), sqlSites(source).map { it.sql })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "@Query(ON_CONFLICT_SET_ACTIVE)",
            "@Query(\"INSERT INTO app_settings (id) VALUES (0) \" + ON_CONFLICT_SET_ACTIVE)",
            "@Query(\"INSERT INTO app_settings (id) VALUES (0) \$ON_CONFLICT_SET_ACTIVE\")",
            "@Query(\"\"\"INSERT INTO app_settings (id) VALUES (0) \${ON_CONFLICT_SET_ACTIVE}\"\"\")",
            "@DatabaseView(viewName = \"one\", value = \"SELECT 1\")",
        ],
    )
    fun `an annotated query that is not a plain literal counts as unchecked`(source: String) {
        assertEquals(listOf(true to null), sqlSites(source).map { it.requiresLiteral to it.sql })
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            "UPSERT | INSERT INTO app_settings (id, activeProfileId) VALUES (0, :id) ON CONFLICT(id) DO UPDATE SET activeProfileId = :id",
            "UPSERT | INSERT INTO route_source (id) VALUES (:id) ON CONFLICT DO NOTHING",
            "TRUE and FALSE literals | SELECT * FROM route_source WHERE enabled = TRUE",
            "RENAME COLUMN | ALTER TABLE route_source RENAME COLUMN comment TO note",
            "RENAME COLUMN | ALTER TABLE route_source RENAME comment TO note",
            "window functions | SELECT id, ROW_NUMBER() OVER (ORDER BY orderIndex) FROM route_source",
            "window functions | SELECT id FROM route_source WINDOW w AS (ORDER BY orderIndex)",
            "NULLS FIRST and NULLS LAST | SELECT * FROM profiles ORDER BY name NULLS LAST",
            "aggregate FILTER | SELECT COUNT(*) FILTER (WHERE enabled) FROM route_source",
            "generated columns | CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)",
            "generated columns | CREATE TABLE t (a INTEGER, b INTEGER AS (a * 2) VIRTUAL)",
            "IIF | SELECT IIF(enabled, 'on', 'off') FROM route_source",
            "UPDATE FROM | UPDATE route_source SET lastError = r.err FROM (SELECT id, err FROM x) AS r WHERE route_source.id = r.id",
            "SUBSTRING | SELECT SUBSTRING(name, 1, 3) FROM profiles",
            "RETURNING | DELETE FROM profiles WHERE id = :id RETURNING name",
            "DROP COLUMN | ALTER TABLE route_source DROP COLUMN comment",
            "MATERIALIZED common table expressions | WITH s AS MATERIALIZED (SELECT * FROM route_source) SELECT * FROM s",
            "built-in math functions | SELECT SQRT(orderIndex) FROM route_source",
            "STRICT tables | CREATE TABLE t (a INTEGER) STRICT",
            "-> and ->> operators | SELECT config ->> '\$.server' FROM profiles",
            "UNIXEPOCH and FORMAT | SELECT UNIXEPOCH() FROM profiles",
            "IS DISTINCT FROM | SELECT * FROM route_source WHERE lastError IS NOT DISTINCT FROM :err",
            "RIGHT and FULL joins | SELECT * FROM profiles FULL OUTER JOIN app_settings ON profiles.id = app_settings.activeProfileId",
            "UNHEX | SELECT UNHEX(name) FROM profiles",
            "OCTET_LENGTH and TIMEDIFF | SELECT OCTET_LENGTH(name) FROM profiles",
            "CONCAT, CONCAT_WS and STRING_AGG | SELECT CONCAT(name, id) FROM profiles",
            "ORDER BY in aggregate arguments | SELECT GROUP_CONCAT(name ORDER BY name) FROM profiles",
            "JSONB functions | SELECT JSONB_EXTRACT(config, '\$.server') FROM profiles",
            "underscores in numeric literals | SELECT * FROM route_source WHERE orderIndex > 1_000",
            "IF | SELECT IF(enabled, 'on', 'off') FROM route_source",
            "UNISTR | SELECT UNISTR(name) FROM profiles",
        ],
    )
    fun `the guard rejects syntax the api 29 sqlite cannot parse`(construct: String, sql: String) {
        assertEquals(listOf(construct), matchingConstructs(sql).map { it.name })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "INSERT INTO app_settings (id) SELECT 0 WHERE NOT EXISTS (SELECT 1 FROM app_settings WHERE id = 0)",
            "INSERT OR IGNORE INTO app_settings (id) VALUES (0)",
            "UPDATE app_settings SET activeProfileId = :id WHERE id = 0",
            "UPDATE route_source SET lastError = (SELECT err FROM x WHERE x.id = route_source.id) WHERE id = :id",
            "SELECT * FROM route_source WHERE comment = 'DO UPDATE' OR comment = 'true' OR comment = 'a->b'",
            "SELECT `format`, \"true\" FROM route_source -- ORDER BY name NULLS LAST",
            "SELECT * FROM profiles ORDER BY updatedAt DESC",
            "SELECT * FROM route_source WHERE id IN (SELECT id FROM route_source ORDER BY orderIndex LIMIT 1)",
            "SELECT SUBSTR(name, 1, 3), IFNULL(lastError, '') FROM route_source WHERE orderIndex > 1000",
            "SELECT * FROM profiles LEFT OUTER JOIN app_settings ON profiles.id = app_settings.activeProfileId",
            "SELECT * FROM route_source WHERE lastError IS NOT NULL AND orderIndex > -1",
            "INSERT INTO log (message) VALUES ('x')",
            "SELECT GROUP_CONCAT(name) FROM profiles",
            "CREATE TABLE IF NOT EXISTS `resolved_cidr` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceId` TEXT NOT NULL, FOREIGN KEY(`sourceId`) REFERENCES `route_source`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE t (id INTEGER PRIMARY KEY ON CONFLICT REPLACE)",
            "PRAGMA defer_foreign_keys = TRUE",
            "ALTER TABLE routing_config ADD COLUMN mtu INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE _new_routing_config RENAME TO routing_config",
            "DROP TABLE IF EXISTS route_source",
            "CREATE TEMP TRIGGER IF NOT EXISTS `room_table_modification_trigger_profiles_UPDATE` AFTER UPDATE " +
                "ON `profiles` BEGIN UPDATE room_table_modification_log SET invalidated = 1 " +
                "WHERE table_id = 0 AND invalidated = 0; END",
        ],
    )
    fun `the guard accepts syntax the api 29 sqlite parses`(sql: String) {
        assertEquals(emptyList<String>(), newerSyntax(sql))
    }

    private fun sqlSites(): List<SqlSite> =
        mainSourceSet.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .sortedBy { it.invariantSeparatorsPath }
            .flatMap { file -> sqlSites(file.readText(), file.relativeTo(mainSourceSet).invariantSeparatorsPath) }
            .toList()

    private fun sqlSites(text: String, path: String = "source"): List<SqlSite> {
        val source = text.replace("\r\n", "\n")
        return SITE.findAll(source).map { match ->
            val line = source.substring(0, match.range.first).count { it == '\n' } + 1
            SqlSite(
                location = "$path:$line",
                requiresLiteral = match.value.startsWith("@"),
                sql = stringLiteralAt(source, match.range.last + 1),
            )
        }.toList()
    }

    private fun stringLiteralAt(source: String, start: Int): String? {
        val parts = mutableListOf<String>()
        var index = start
        while (true) {
            val (text, end) = when {
                source.startsWith(RAW_QUOTE, index) -> rawLiteralAt(source, index + RAW_QUOTE.length)
                source.startsWith("\"", index) -> escapedLiteralAt(source, index + 1)
                else -> null
            } ?: return null
            parts += text
            index = CONCATENATION.matchAt(source, end)?.range?.let { it.last + 1 } ?: return parts.joinToString("")
        }
    }

    private fun rawLiteralAt(source: String, start: Int): Pair<String, Int>? {
        val end = source.indexOf(RAW_QUOTE, start)
        if (end < 0) return null
        val text = source.substring(start, end)
        return if (TEMPLATE.containsMatchIn(text)) null else text to end + RAW_QUOTE.length
    }

    private fun escapedLiteralAt(source: String, start: Int): Pair<String, Int>? {
        val text = StringBuilder()
        var index = start
        while (index < source.length) {
            when (val char = source[index]) {
                '"' -> return text.toString() to index + 1
                '$' -> if (TEMPLATE.matchesAt(source, index)) return null else text.append(char)
                '\\' -> {
                    index++
                    text.append(
                        when (source.getOrNull(index)) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            null -> return null
                            else -> source[index]
                        },
                    )
                }
                else -> text.append(char)
            }
            index++
        }
        return null
    }

    private fun newerSyntax(sql: String): List<String> =
        matchingConstructs(sql).map { "${it.name}, added in SQLite ${it.since}" }

    private fun matchingConstructs(sql: String): List<Construct> {
        val statement = sanitize(sql)
        val flattened = flattenParentheses(statement)
        return CONSTRUCTS.filter { it.pattern.containsMatchIn(if (it.flatten) flattened else statement) }
    }

    private fun sanitize(sql: String): String =
        LEXICAL.replace(sql) { match ->
            when (match.value.first()) {
                '\'' -> "''"
                '"', '`' -> "x"
                else -> " "
            }
        }

    private fun flattenParentheses(sql: String): String {
        var current = sql
        while (true) {
            val next = INNERMOST_PARENTHESES.replace(current, "_")
            if (next == current) return current
            current = next
        }
    }

    private data class SqlSite(val location: String, val requiresLiteral: Boolean, val sql: String?)

    private class Construct(val name: String, val since: String, pattern: String, val flatten: Boolean = false) {
        val pattern = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private companion object {
        const val RAW_QUOTE = "\"\"\""

        val SITE = Regex(
            """(@(?:androidx\.room\.)?(?:Query|DatabaseView)|\b(?:execSQL|execSql|execPerConnectionSQL|rawQuery|""" +
                """compileStatement|prepare|usePrepared|query|SimpleSQLiteQuery|RoomRawQuery))""" +
                """\s*\(\s*(?:(?:value|sql|query)\s*=\s*)?""",
        )
        val CONCATENATION = Regex("""\s*\+\s*""")
        val TEMPLATE = Regex("""\$[A-Za-z_{]""")
        val LEXICAL = Regex("""'(?:[^']|'')*'|"(?:[^"]|"")*"|`[^`]*`|--[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val INNERMOST_PARENTHESES = Regex("""\([^()]*\)""")

        fun functions(vararg names: String): String =
            """(?<!\b(?:INTO|TABLE|EXISTS|FROM|JOIN|UPDATE|REFERENCES)\s{1,16})\b(?:${names.joinToString("|")})\s*\("""

        val CONSTRUCTS = listOf(
            Construct("TRUE and FALSE literals", "3.23.0", """^(?!\s*PRAGMA\b)[\s\S]*\b(?:TRUE|FALSE)\b"""),
            Construct("UPSERT", "3.24.0", """\bDO\s+(?:UPDATE|NOTHING)\b"""),
            Construct("RENAME COLUMN", "3.25.0", """\bALTER\s+TABLE\s+\S+\s+RENAME\s+(?:COLUMN\s+)?(?!TO\b)\S+\s+TO\b"""),
            Construct("window functions", "3.25.0", """\bOVER\s*\(|\bWINDOW\s+\w+\s+AS\s*\("""),
            Construct("NULLS FIRST and NULLS LAST", "3.30.0", """\bNULLS\s+(?:FIRST|LAST)\b"""),
            Construct("aggregate FILTER", "3.30.0", """\bFILTER\s*\(\s*WHERE\b"""),
            Construct("generated columns", "3.31.0", """\bGENERATED\s+ALWAYS\b|\)\s*(?:STORED|VIRTUAL)\b"""),
            Construct("IIF", "3.32.0", functions("IIF")),
            Construct("UPDATE FROM", "3.33.0", """\bUPDATE\s+(?:OR\s+\w+\s+)?\S+\s+SET\b[^;]*\bFROM\b""", flatten = true),
            Construct("SUBSTRING", "3.34.0", functions("SUBSTRING")),
            Construct("RETURNING", "3.35.0", """\bRETURNING\b"""),
            Construct("DROP COLUMN", "3.35.0", """\bALTER\s+TABLE\s+\S+\s+DROP\b"""),
            Construct("MATERIALIZED common table expressions", "3.35.0", """\bMATERIALIZED\b"""),
            Construct(
                "built-in math functions",
                "3.35.0",
                functions(
                    "ACOS", "ACOSH", "ASIN", "ASINH", "ATAN", "ATAN2", "ATANH", "CEIL", "CEILING", "COS", "COSH",
                    "DEGREES", "EXP", "FLOOR", "LN", "LOG", "LOG10", "LOG2", "MOD", "PI", "POW", "POWER", "RADIANS",
                    "SIN", "SINH", "SQRT", "TAN", "TANH", "TRUNC",
                ),
            ),
            Construct("STRICT tables", "3.37.0", """[),]\s*STRICT\b"""),
            Construct("-> and ->> operators", "3.38.0", """->"""),
            Construct("UNIXEPOCH and FORMAT", "3.38.0", functions("UNIXEPOCH", "FORMAT")),
            Construct("IS DISTINCT FROM", "3.39.0", """\bIS\s+(?:NOT\s+)?DISTINCT\s+FROM\b"""),
            Construct("RIGHT and FULL joins", "3.39.0", """\b(?:RIGHT|FULL)\s+(?:OUTER\s+)?JOIN\b"""),
            Construct("UNHEX", "3.41.0", functions("UNHEX")),
            Construct("OCTET_LENGTH and TIMEDIFF", "3.43.0", functions("OCTET_LENGTH", "TIMEDIFF")),
            Construct("CONCAT, CONCAT_WS and STRING_AGG", "3.44.0", functions("CONCAT", "CONCAT_WS", "STRING_AGG")),
            Construct(
                "ORDER BY in aggregate arguments",
                "3.44.0",
                """\b(?!(?:OVER|AS)\b)\w+\s*\((?:(?!\bSELECT\b)(?:[^()]|\([^()]*\)))*\bORDER\s+BY\b""",
            ),
            Construct("JSONB functions", "3.45.0", """\bJSONB(?:_\w+)?\s*\("""),
            Construct(
                "underscores in numeric literals",
                "3.46.0",
                """(?<![\w.])(?:0x[0-9a-f]+|\d+(?:\.\d*)?|\.\d+)_[0-9a-f]""",
            ),
            Construct("IF", "3.48.0", functions("IF")),
            Construct("UNISTR", "3.50.0", functions("UNISTR", "UNISTR_QUOTE")),
        )
    }
}
