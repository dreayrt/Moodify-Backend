package com.laphuth.moodify.services;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminTransactionsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AdminService service = new AdminService(jdbc, null, null, null, null, null, null);

    @Test
    void pendingTransactionHasCreationTimeAndNoPaymentTime() throws Exception {
        transactionRow(null, Timestamp.valueOf("2026-11-04 09:15:00"));

        Map<String, Object> row = service.getTransactions().get(0);

        assertThat(row.get("paidAt")).isEqualTo("");
        assertThat(row.get("createdAt")).isEqualTo("04/11/2026 09:15");
        assertThat(row.get("status")).isEqualTo("PENDING");
    }

    @Test
    void paidTransactionKeepsActualPaymentAndCreationTimes() throws Exception {
        transactionRow(Timestamp.valueOf("2026-11-04 09:20:00"), Timestamp.valueOf("2026-11-04 09:15:00"));

        Map<String, Object> row = service.getTransactions().get(0);

        assertThat(row.get("paidAt")).isEqualTo("04/11/2026 09:20");
        assertThat(row.get("createdAt")).isEqualTo("04/11/2026 09:15");
    }

    @Test
    void absentTimestampsAreNotReplacedWithAnInventedDate() throws Exception {
        transactionRow(null, null);

        Map<String, Object> row = service.getTransactions().get(0);

        assertThat(row.get("paidAt")).isEqualTo("");
        assertThat(row.get("createdAt")).isEqualTo("");
    }

    @SuppressWarnings("unchecked")
    private void transactionRow(Timestamp paidAt, Timestamp createdAt) {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            // Derive ResultSet columns from the real SELECT projection. Reading an
            // unselected column throws SQLException, as the production driver does.
            String sql = invocation.getArgument(0);
            String[] fields = sql.substring("SELECT ".length(), sql.indexOf(" FROM ")).split(",");
            RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
            metadata.setColumnCount(fields.length);
            for (int i = 0; i < fields.length; i++) {
                String field = fields[i].trim();
                String column = field.contains(" as ") ? field.substring(field.indexOf(" as ") + 4)
                        : field.substring(field.lastIndexOf('.') + 1);
                metadata.setColumnName(i + 1, column);
                metadata.setColumnLabel(i + 1, column);
                metadata.setColumnType(i + 1, switch (column) {
                    case "id", "subscription_id" -> Types.BIGINT;
                    case "amount" -> Types.DOUBLE;
                    case "paid_at", "created_at" -> Types.TIMESTAMP;
                    default -> Types.VARCHAR;
                });
            }
            try (CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet()) {
                rows.setMetaData(metadata);
                rows.moveToInsertRow();
                for (int i = 1; i <= fields.length; i++) {
                    String column = metadata.getColumnName(i);
                    switch (column) {
                        case "id", "subscription_id" -> rows.updateLong(i, 1L);
                        case "amount" -> rows.updateDouble(i, 57000);
                        case "paid_at" -> { if (paidAt != null) rows.updateTimestamp(i, paidAt); else rows.updateNull(i); }
                        case "created_at" -> { if (createdAt != null) rows.updateTimestamp(i, createdAt); else rows.updateNull(i); }
                        case "status" -> rows.updateString(i, paidAt != null ? "SUCCESS" : "PENDING");
                        default -> rows.updateString(i, "");
                    }
                }
                rows.insertRow();
                rows.moveToCurrentRow();
                rows.beforeFirst();
                rows.next();
                RowMapper<Map<String, Object>> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(rows, 0));
            }
        });
    }
}
