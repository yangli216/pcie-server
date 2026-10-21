package com.regionalai.floatingball.server.modules.knowledge.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.knowledge.config.DrugInstructionProperties;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionDetail;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionItem;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionSearchRequest;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionSearchResponse;
import com.regionalai.floatingball.server.modules.knowledge.repository.DrugInstructionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class DrugInstructionService {
    private static final Logger log = LoggerFactory.getLogger(DrugInstructionService.class);
    private final DrugInstructionProperties properties;
    private final DrugInstructionRepository repository;

    public DrugInstructionService(DrugInstructionProperties properties, DrugInstructionRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    public DrugInstructionSearchResponse search(DrugInstructionSearchRequest request) {
        String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim();
        if (keyword.isEmpty() || keyword.length() > 128 || request.getCurrent() < 1
            || request.getCurrent() > 1000 || request.getSize() < 1 || request.getSize() > 100) {
            throw new BusinessException("DRUG_INSTRUCTION_INVALID_QUERY", "请检查药品检索内容和分页参数");
        }
        requireConfigured();
        try {
            List<DrugInstructionItem> rows = repository.search(keyword, (request.getCurrent() - 1) * request.getSize(), request.getSize() + 1);
            boolean hasMore = rows.size() > request.getSize();
            return new DrugInstructionSearchResponse(request.getCurrent(), request.getSize(), hasMore,
                new ArrayList<>(rows.subList(0, Math.min(rows.size(), request.getSize()))));
        } catch (SQLException | RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    public Optional<DrugInstructionDetail> find(String productId) {
        if (!StringUtils.hasText(productId) || productId.length() > 64) {
            throw new BusinessException("DRUG_INSTRUCTION_INVALID_ID", "药品标识不正确");
        }
        requireConfigured();
        try {
            return repository.find(productId);
        } catch (SQLException | RuntimeException ex) {
            throw unavailable(ex);
        }
    }

    private void requireConfigured() {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getJdbcUrl())
            || !properties.getJdbcUrl().startsWith("jdbc:mysql:") || !StringUtils.hasText(properties.getUsername())
            || !StringUtils.hasText(properties.getPassword())) {
            throw new ServiceUnavailableException("DRUG_INSTRUCTIONS_UNAVAILABLE", "药品说明书服务未启用或尚未配置，请联系管理员");
        }
    }

    private ServiceUnavailableException unavailable(Exception ex) {
        // Never log JDBC URLs, passwords, or driver exception messages.
        log.warn("Drug instruction lookup failed: type={}, sqlState={}", ex.getClass().getSimpleName(),
            ex instanceof SQLException ? ((SQLException) ex).getSQLState() : "N/A");
        return new ServiceUnavailableException("DRUG_INSTRUCTIONS_UNAVAILABLE", "药品说明书暂时无法查询，请稍后重试");
    }
}
