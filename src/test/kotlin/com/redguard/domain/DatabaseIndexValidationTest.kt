package com.redguard.domain

import com.redguard.infrastructure.config.JpaConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.sql.ResultSet

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig::class)
class DatabaseIndexValidationTest @Autowired constructor(
    private val jdbcTemplate: JdbcTemplate
) {

    @Test
    fun `사용량_및_한도초과_테이블에_대량조회_인덱스가_정의된다`() {
        val usageIndexes = fetchIndexes("USAGE_SNAPSHOTS")
        assertThat(usageIndexes).anySatisfy { assertThat(it.columns).containsExactly("TENANT_ID", "PERIOD_TYPE", "SNAPSHOT_DATE", "ID") }
        assertThat(usageIndexes).anySatisfy { assertThat(it.columns).containsExactly("TENANT_ID", "USER_ID", "PERIOD_TYPE", "SNAPSHOT_DATE", "ID") }
        assertThat(usageIndexes).anySatisfy { assertThat(it.columns).containsExactly("TENANT_ID", "API_PATH", "PERIOD_TYPE", "SNAPSHOT_DATE", "ID") }
        assertThat(usageIndexes).anySatisfy { assertThat(it.columns).containsExactly("TENANT_ID", "USER_ID", "API_PATH", "SNAPSHOT_DATE", "PERIOD_TYPE") }

        val limitIndexes = fetchIndexes("LIMIT_HIT_LOGS")
        assertThat(limitIndexes).anySatisfy { assertThat(it.columns).containsExactly("TENANT_ID", "OCCURRED_AT", "ID") }
        assertThat(limitIndexes).anySatisfy { assertThat(it.columns).containsExactly("TENANT_ID", "USER_ID", "OCCURRED_AT", "ID") }
        assertThat(limitIndexes).anySatisfy { assertThat(it.columns).containsExactly("TENANT_ID", "API_PATH", "OCCURRED_AT", "ID") }
    }

    private fun fetchIndexes(table: String): List<IndexDefinition> {
        val rows = jdbcTemplate.query(
            """
            select i.index_name, ic.column_name, ic.ordinal_position
            from information_schema.indexes i
            join information_schema.index_columns ic
              on i.index_name = ic.index_name
             and i.table_name = ic.table_name
             and i.table_schema = ic.table_schema
            where i.table_name = ?
              and i.table_schema = schema()
            order by i.index_name, ic.ordinal_position
            """.trimIndent(),
            { rs: ResultSet, _: Int ->
                Triple(
                    rs.getString("index_name").uppercase(),
                    rs.getString("column_name").uppercase(),
                    rs.getInt("ordinal_position")
                )
            },
            table
        )

        return rows.groupBy { it.first }.map { (name, columns) ->
            IndexDefinition(
                name = name,
                columns = columns.sortedBy { it.third }.map { it.second }
            )
        }
    }

    private data class IndexDefinition(
        val name: String,
        val columns: List<String>
    )
}
