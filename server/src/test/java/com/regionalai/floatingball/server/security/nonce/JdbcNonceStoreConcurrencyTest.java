package com.regionalai.floatingball.server.security.nonce;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcNonceStoreConcurrencyTest {

    @Test
    void concurrentNodesShouldAllowExactlyOneClaim() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        int contenders = 32;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<Future<Boolean>>();

        try {
            for (int index = 0; index < contenders; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return new JdbcNonceStore(jdbcTemplate).claim(
                        "DEV-CONCURRENT",
                        "nonce-shared",
                        System.currentTimeMillis() + 60_000L
                    );
                }));
            }
            ready.await();
            start.countDown();

            int claimed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    claimed++;
                }
            }
            assertThat(claimed).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM c_security_request_nonce WHERE id_device = ? AND nonce_value = ?",
                Integer.class,
                "DEV-CONCURRENT",
                "nonce-shared"
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameNonceShouldRemainIndependentAcrossDevices() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        createSchema(jdbcTemplate);
        JdbcNonceStore firstNode = new JdbcNonceStore(jdbcTemplate);
        JdbcNonceStore secondNode = new JdbcNonceStore(jdbcTemplate);
        long expiresAt = System.currentTimeMillis() + 60_000L;

        assertThat(firstNode.claim("DEV-A", "same-random-value", expiresAt)).isTrue();
        assertThat(secondNode.claim("DEV-B", "same-random-value", expiresAt)).isTrue();
    }

    private JdbcTemplate jdbcTemplate() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:nonce_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return new JdbcTemplate(dataSource);
    }

    private void createSchema(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute(
            "CREATE TABLE c_security_request_nonce ("
                + "id_device VARCHAR(32) NOT NULL, "
                + "nonce_value VARCHAR(64) NOT NULL, "
                + "expires_at TIMESTAMP NOT NULL, "
                + "insert_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, "
                + "PRIMARY KEY (id_device, nonce_value))"
        );
        jdbcTemplate.execute(
            "CREATE INDEX idx_c_security_nonce_exp ON c_security_request_nonce (expires_at)"
        );
    }
}
