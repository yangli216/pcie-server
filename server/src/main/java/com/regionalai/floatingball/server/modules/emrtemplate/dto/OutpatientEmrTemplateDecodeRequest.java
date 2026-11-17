package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.io.IOException;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(onlyExplicitlyIncluded = true)
public class OutpatientEmrTemplateDecodeRequest extends StrictOutpatientEmrSnapshotDto {

    @ToString.Include
    @JsonDeserialize(using = ExactStringDeserializer.class)
    private String schemaVersion;

    @JsonDeserialize(using = ExactStringDeserializer.class)
    private String templateHtml;

    @JsonDeserialize(using = ExactStringDeserializer.class)
    private String templateDefinition;

    /** Reject Jackson's default number/boolean-to-string coercion for this wire contract. */
    public static class ExactStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                throw JsonMappingException.from(parser, "模板解码字段必须是字符串");
            }
            return parser.getText();
        }
    }
}
