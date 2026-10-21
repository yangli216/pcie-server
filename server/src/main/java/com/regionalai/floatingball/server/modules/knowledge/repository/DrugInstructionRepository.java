package com.regionalai.floatingball.server.modules.knowledge.repository;

import com.regionalai.floatingball.server.modules.knowledge.config.DrugInstructionProperties;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionDetail;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionItem;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Repository;
import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** External MySQL view only. Never expose this pool as a Spring DataSource bean. */
@Repository
public class DrugInstructionRepository {
    private static final String COLUMNS = "product_id, product_name, generic_name, drug_spec, producer, drug_form, flmc";
    private final DrugInstructionProperties properties;
    private HikariDataSource pool;

    public DrugInstructionRepository(DrugInstructionProperties properties) {
        this.properties = properties;
    }

    private synchronized HikariDataSource pool() {
        if (pool == null) {
            HikariConfig config = new HikariConfig();
            config.setPoolName("drug-instructions");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
            config.setJdbcUrl(properties.getJdbcUrl());
            config.setUsername(properties.getUsername());
            config.setPassword(properties.getPassword());
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setReadOnly(true);
            config.setConnectionTimeout(5000);
            config.setValidationTimeout(3000);
            config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("connectTimeout", "5000");
            config.addDataSourceProperty("socketTimeout", "10000");
            config.addDataSourceProperty("characterEncoding", "UTF-8");
            pool = new HikariDataSource(config);
        }
        return pool;
    }

    public List<DrugInstructionItem> search(String keyword, int offset, int limit) throws SQLException {
        // UNION materializes deduplicated matches before sorting. The external view has
        // many joined duplicates; sorting it directly can exceed the query deadline.
        String projection = "SELECT DISTINCT product_id, product_name,"
            + " CASE WHEN product_name = ? OR generic_name = ? THEN 0 ELSE 1 END AS match_priority FROM hzsz WHERE ";
        String sql = "SELECT product_id FROM (" + projection + "product_name LIKE ? ESCAPE '!' UNION "
            + projection + "generic_name LIKE ? ESCAPE '!' UNION " + projection + "py LIKE ? ESCAPE '!') matches"
            + " GROUP BY product_id ORDER BY MIN(match_priority), MIN(product_name), product_id LIMIT ? OFFSET ?";
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        try (Connection connection = pool().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i = 0; i < 3; i++) {
                statement.setString(i * 3 + 1, keyword);
                statement.setString(i * 3 + 2, keyword);
                statement.setString(i * 3 + 3, pattern);
            }
            statement.setInt(10, limit);
            statement.setInt(11, offset);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) ids.add(text(rs, "product_id"));
            }
            List<DrugInstructionItem> items = new ArrayList<>();
            // Read one actual source row per ID rather than combine fields across aliases.
            // At most size + 1 small, indexed lookups; never fetch summary for this list.
            try (PreparedStatement metadata = connection.prepareStatement("SELECT " + COLUMNS + " FROM hzsz WHERE product_id = ? LIMIT 1")) {
                metadata.setQueryTimeout(5);
                for (String id : ids) {
                    metadata.setString(1, id);
                    try (ResultSet rs = metadata.executeQuery()) {
                        if (rs.next()) items.add(new DrugInstructionItem(text(rs, "product_id"), text(rs, "product_name"),
                            text(rs, "generic_name"), text(rs, "drug_spec"), text(rs, "producer"), text(rs, "drug_form"), text(rs, "flmc")));
                    }
                }
            }
            return items;
        }
    }

    public Optional<DrugInstructionDetail> find(String productId) throws SQLException {
        String sql = "SELECT " + COLUMNS + ", summary, create_date FROM hzsz WHERE product_id = ? LIMIT 1";
        try (Connection connection = pool().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, productId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new DrugInstructionDetail(text(rs, "product_id"), text(rs, "product_name"),
                    text(rs, "generic_name"), text(rs, "drug_spec"), text(rs, "producer"), text(rs, "drug_form"),
                    text(rs, "flmc"), text(rs, "summary"), text(rs, "create_date")));
            }
        }
    }

    private String text(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    @PreDestroy
    public synchronized void close() {
        if (pool != null) pool.close();
    }
}
