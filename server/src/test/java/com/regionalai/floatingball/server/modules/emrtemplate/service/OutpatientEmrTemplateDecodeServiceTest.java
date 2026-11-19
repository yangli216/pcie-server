package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDecodeRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDecodedResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutpatientEmrTemplateDecodeServiceTest {
    private static final String HTML = " \r\n<section data-article=\"主诉\">中文咳嗽🙂</section>\n ";
    private static final String DEFINITION = " \r\n[{\"ID\":\"chief\",\"NAME\":\"主诉\",\"ARTICLE\":\"主诉\",\"eles\":[]}]\n ";
    private final ObjectMapper mapper = new ObjectMapper();
    private final OutpatientEmrTemplateDecodeService service = new OutpatientEmrTemplateDecodeService(mapper);

    @Test
    void decodesUtf8WithoutTrimmingNormalizingOrExecutingTemplateScripts() {
        String html = HTML + "<script>throw new Error('must stay inert')</script>";
        OutpatientEmrTemplateDecodedResponse result = service.decodePair(htmlUri(html), definitionUri(DEFINITION));
        assertEquals("outpatient-emr-template-decoded.v1", result.getSchemaVersion());
        assertEquals(html, result.getTemplateHtml());
        assertEquals(DEFINITION, result.getTemplateDefinition());
    }

    @Test
    void decodesTheSuppliedFullTemplateAsExactSourceBytes() throws Exception {
        String html = resource("his-internal-medicine-template.html");
        String definition = resource("his-internal-medicine-template.json");
        OutpatientEmrTemplateDecodedResponse result = service.decodePair(htmlUri(html), definitionUri(definition));
        assertEquals(html, result.getTemplateHtml());
        assertEquals(definition, result.getTemplateDefinition());
        assertEquals(11, mapper.readTree(result.getTemplateDefinition()).size());
        assertEquals(66, mapper.readTree(result.getTemplateDefinition()).findValues("TYPE").size());
    }

    @Test
    void returnsEverySourceChapterIncludingPartialChaptersAndUntrimmedDisplayNames() {
        String definition = "[null,{\"ID\":\"incomplete\",\"eles\":[{\"TEXT\":\"合成背景\"}]},"
            + "{\"ID\":\"history\",\"NAME\":\" 既往史 \",\"ARTICLE\":\"病史\",\"eles\":[{\"ID\":\"priorVisit\"}]}]";
        OutpatientEmrTemplateDecodeRequest request = request();
        request.setTemplateDefinition(definitionUri(definition));
        OutpatientEmrTemplateDecodedResponse response = service.decode(device(), request);
        assertEquals(definition, response.getTemplateDefinition());
        assertEquals(HTML, response.getTemplateHtml());
    }

    @Test
    void requiresTheExactPairOfDataUriPrefixes() {
        String[] htmlSources = { null, HTML, base64(HTML), "data:text/html;base64," + base64(HTML),
            "data:text/html;charset=UTF-8;base64," + base64(HTML),
            " " + htmlUri(HTML), definitionUri(DEFINITION) };
        for (String html : htmlSources) {
            assertThrows(BusinessException.class, () -> service.decodePair(html, definitionUri(DEFINITION)));
        }
        assertThrows(BusinessException.class, () -> service.decodePair(htmlUri(HTML), DEFINITION));
        assertThrows(BusinessException.class, () -> service.decodePair(htmlUri(HTML), null));
        assertThrows(BusinessException.class, () -> service.decodePair(htmlUri(HTML), htmlUri(HTML)));
    }

    @Test
    void rejectsNonCanonicalBase64AndInvalidUtf8WithoutExposingInput() {
        String[] invalid = { "", "Zg", "Zh==", "Zg==\n", "Zg--", "%%%%", "wyg=" };
        for (String encoded : invalid) {
            BusinessException error = assertThrows(BusinessException.class, () -> service.decodePair(
                OutpatientEmrTemplateDecodeService.HTML_PREFIX + encoded, definitionUri(DEFINITION)
            ));
            assertFalse(error.getMessage().contains("wyg="));
            assertFalse(error.getMessage().contains("%%%%"));
        }
    }

    @Test
    void rejectsInvalidHtmlAndMalformedDefinitionEnvelopes() {
        for (String html : Arrays.asList("", "   ", "咳嗽", "<>", "<!-- comment only -->")) {
            assertThrows(BusinessException.class, () -> service.decodePair(htmlUri(html), definitionUri(DEFINITION)));
        }
        for (String definition : Arrays.asList("", "[]", "{}", "<xml/>",
            DEFINITION + "{}", DEFINITION.replace("\"ID\":\"chief\"", "\"ID\":\"first\",\"ID\":\"chief\""))) {
            assertThrows(BusinessException.class, () -> service.decodePair(htmlUri(HTML), definitionUri(definition)));
        }
    }

    @Test
    void acceptsExactlyOneMebibyteAndRejectsOneExtraDecodedByte() {
        char[] padding = new char[1024 * 1024 - 7];
        Arrays.fill(padding, ' ');
        String html = "<p>" + new String(padding) + "</p>";
        assertEquals(1024 * 1024, html.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(html, service.decodePair(htmlUri(html), definitionUri(DEFINITION)).getTemplateHtml());
        assertThrows(BusinessException.class, () -> service.decodePair(htmlUri(html + " "), definitionUri(DEFINITION)));
        assertThrows(BusinessException.class, () -> service.decodePair(htmlUri(HTML), definitionUri(html + " ")));
    }

    @Test
    void requiresAuthenticatedDeviceSchemaAndNoUnknownFields() throws Exception {
        OutpatientEmrTemplateDecodeRequest request = request();
        assertEquals(HTML, service.decode(device(), request).getTemplateHtml());
        assertThrows(BusinessException.class, () -> service.decode(null, request));
        AiDevice noOrg = device(); noOrg.setIdOrg(null);
        assertThrows(BusinessException.class, () -> service.decode(noOrg, request));
        request.setSchemaVersion("outpatient-emr-template-decode.v0");
        assertThrows(BusinessException.class, () -> service.decode(device(), request));
        request.setSchemaVersion("outpatient-emr-template-decode.v1");
        request.captureUnknownField("legacySource", "sensitive-source");
        BusinessException error = assertThrows(BusinessException.class, () -> service.decode(device(), request));
        assertFalse(error.getMessage().contains("sensitive-source"));
        assertThrows(BusinessException.class, () -> service.decode(device(), null));
    }

    @Test
    void dtoDiagnosticsNeverIncludeEncodedOrDecodedSources() {
        OutpatientEmrTemplateDecodeRequest request = request();
        OutpatientEmrTemplateDecodedResponse result = service.decode(device(), request);
        assertFalse(request.toString().contains(request.getTemplateHtml()));
        assertFalse(request.toString().contains(request.getTemplateDefinition()));
        assertFalse(result.toString().contains(HTML));
        assertFalse(result.toString().contains(DEFINITION));
    }

    private OutpatientEmrTemplateDecodeRequest request() {
        OutpatientEmrTemplateDecodeRequest request = new OutpatientEmrTemplateDecodeRequest();
        request.setSchemaVersion("outpatient-emr-template-decode.v1");
        request.setTemplateHtml(htmlUri(HTML)); request.setTemplateDefinition(definitionUri(DEFINITION));
        return request;
    }

    private AiDevice device() {
        AiDevice device = new AiDevice(); device.setIdDevice("SIM-DEVICE"); device.setIdOrg("SIM-ORG"); return device;
    }

    private String resource(String name) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fixtures/outpatient-emr/" + name)) {
            return new String(org.springframework.util.StreamUtils.copyToByteArray(stream), StandardCharsets.UTF_8);
        }
    }

    private String htmlUri(String value) { return OutpatientEmrTemplateDecodeService.HTML_PREFIX + base64(value); }
    private String definitionUri(String value) { return OutpatientEmrTemplateDecodeService.DEFINITION_PREFIX + base64(value); }
    private String base64(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
}
