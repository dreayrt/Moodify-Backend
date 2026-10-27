package com.laphuth.moodify.services;

import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentLeadCatalogContractResolutionTest {
    private static final String CONTRACT_CODE = "CTR-MUSIC-2026/89";
    private static final String CONTRACT_FOR_DISTRIBUTOR_SQL =
        "SELECT id FROM distribution_contracts WHERE contract_code = ? AND distributor_id = ? LIMIT 1";
    private static final String CONTRACT_DISTRIBUTOR_SQL =
        "SELECT distributor_id FROM distribution_contracts WHERE contract_code = ? LIMIT 1";

    @Test
    void resolvesRegisteredContractCodeForSelectedDistributor() {
        StubContractJdbcTemplate jdbcTemplate = new StubContractJdbcTemplate(89L, null);
        ContentLeadCatalogService service = serviceWith(jdbcTemplate);

        assertThat(service.resolveValidContractCode(4L, CONTRACT_CODE)).isEqualTo(89L);
    }

    @Test
    void leavesUnregisteredDocumentReferenceUnlinked() {
        StubContractJdbcTemplate jdbcTemplate = new StubContractJdbcTemplate(null, null);
        ContentLeadCatalogService service = serviceWith(jdbcTemplate);

        assertThat(service.resolveValidContractCode(4L, CONTRACT_CODE)).isNull();
    }

    @Test
    void rejectsContractCodeAlreadyRegisteredToAnotherDistributor() {
        StubContractJdbcTemplate jdbcTemplate = new StubContractJdbcTemplate(null, 7L);
        ContentLeadCatalogService service = serviceWith(jdbcTemplate);

        assertThatThrownBy(() -> service.resolveValidContractCode(4L, CONTRACT_CODE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Mã hợp đồng đã đăng ký cho một đơn vị phân phối khác.");
    }

    private ContentLeadCatalogService serviceWith(JdbcTemplate jdbcTemplate) {
        return new ContentLeadCatalogService(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            jdbcTemplate,
            null,
            null
        );
    }

    private static class StubContractJdbcTemplate extends JdbcTemplate {
        private final Long matchingContractId;
        private final Long registeredDistributorId;

        private StubContractJdbcTemplate(Long matchingContractId, Long registeredDistributorId) {
            this.matchingContractId = matchingContractId;
            this.registeredDistributorId = registeredDistributorId;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            Object value;
            if (CONTRACT_FOR_DISTRIBUTOR_SQL.equals(sql)) {
                value = matchingContractId;
            } else if (CONTRACT_DISTRIBUTOR_SQL.equals(sql)) {
                value = registeredDistributorId;
            } else {
                throw new AssertionError("Unexpected SQL: " + sql);
            }
            if (value == null) {
                throw new EmptyResultDataAccessException(1);
            }
            return requiredType.cast(value);
        }
    }
}
