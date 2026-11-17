package com.regionalai.floatingball.server.modules.emrtemplate.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@Data
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class OutpatientEmrTemplateDecodedResponse {
    @ToString.Include
    private String schemaVersion;
    private String templateHtml;
    private String templateDefinition;
}
