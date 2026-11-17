package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDecodeRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDecodedResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Stateless transport decoding. HTML sanitization and field pairing remain client responsibilities. */
@Service
public class OutpatientEmrTemplateDecodeService {
    public static final String HTML_PREFIX = "data:text/html;charset=utf-8;base64,";
    public static final String DEFINITION_PREFIX = "data:application/json;charset=utf-8;base64,";
    private static final String REQUEST_SCHEMA = "outpatient-emr-template-decode.v1";
    private static final String RESPONSE_SCHEMA = "outpatient-emr-template-decoded.v1";
    private static final int MAX_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_BASE64_LENGTH = ((MAX_SOURCE_BYTES + 2) / 3) * 4;
    private static final Pattern HTML_ELEMENT = Pattern.compile("<[A-Za-z][A-Za-z0-9:-]*(?:\\s[^<>]*)?/?>");
    private final ObjectMapper definitionMapper;

    public OutpatientEmrTemplateDecodeService(ObjectMapper objectMapper) {
        this.definitionMapper = objectMapper.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public OutpatientEmrTemplateDecodedResponse decode(AiDevice device, OutpatientEmrTemplateDecodeRequest request) {
        if (device == null || !StringUtils.hasText(device.getIdDevice()) || !StringUtils.hasText(device.getIdOrg())) {
            throw new BusinessException("设备认证上下文缺失");
        }
        if (request == null) throw new BusinessException("请求体不能为空");
        if (!request.getUnknownFields().isEmpty()) throw new BusinessException("模板解码请求包含未支持字段");
        if (!REQUEST_SCHEMA.equals(request.getSchemaVersion())) throw new BusinessException("模板解码 schemaVersion 无效");
        return decodePair(request.getTemplateHtml(), request.getTemplateDefinition());
    }

    public OutpatientEmrTemplateDecodedResponse decodePair(String htmlSource, String definitionSource) {
        String html = decodeSource(htmlSource, HTML_PREFIX, "templateHtml");
        String definition = decodeSource(definitionSource, DEFINITION_PREFIX, "templateDefinition");
        if (!html.trim().startsWith("<") || !HTML_ELEMENT.matcher(html).find()) {
            throw new BusinessException("templateHtml 解码后必须是非空 HTML");
        }
        validateDefinition(definition);
        return new OutpatientEmrTemplateDecodedResponse(RESPONSE_SCHEMA, html, definition);
    }

    /** Detect data URIs, including unsupported case/whitespace variants, without guessing bare Base64. */
    public static boolean hasDataUriSource(String source) {
        return source != null && source.trim().regionMatches(true, 0, "data:", 0, 5);
    }

    private String decodeSource(String source, String prefix, String field) {
        if (source == null || !source.startsWith(prefix)) {
            throw new BusinessException(field + " 必须使用约定的 Base64 data URI");
        }
        if (source.length() > prefix.length() + MAX_BASE64_LENGTH) {
            throw new BusinessException(field + " Base64 长度超过 1 MiB 限制");
        }
        String encoded = source.substring(prefix.length());
        if (encoded.isEmpty() || encoded.length() > MAX_BASE64_LENGTH || encoded.length() % 4 != 0) {
            throw new BusinessException(field + " Base64 长度无效或超过 1 MiB 限制");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(field + " 必须是规范的标准 Base64");
        }
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
            throw new BusinessException(field + " 必须是规范的标准 Base64");
        }
        if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) {
            throw new BusinessException(field + " 解码后必须非空且不能超过 1 MiB");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new BusinessException(field + " 必须是合法 UTF-8");
        }
    }

    private void validateDefinition(String definition) {
        JsonNode chapters;
        try {
            chapters = definitionMapper.readTree(definition);
        } catch (IOException error) {
            throw new BusinessException("templateDefinition 解码后必须是合法章节 JSON 数组");
        }
        if (chapters == null || !chapters.isArray() || chapters.size() == 0) {
            throw new BusinessException("templateDefinition 解码后必须是非空章节 JSON 数组");
        }
        for (JsonNode chapter : chapters) {
            if (!chapter.isObject() || !isExactText(chapter.get("ID")) || !isExactText(chapter.get("NAME"))
                || !isExactText(chapter.get("ARTICLE")) || !chapter.path("eles").isArray()) {
                throw new BusinessException("templateDefinition 章节必须包含 ID、NAME、ARTICLE 和 eles 数组");
            }
        }
    }

    private boolean isExactText(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isEmpty()
            && value.textValue().equals(value.textValue().trim());
    }
}
