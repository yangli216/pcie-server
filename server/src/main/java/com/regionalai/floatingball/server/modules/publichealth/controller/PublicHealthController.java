package com.regionalai.floatingball.server.modules.publichealth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.publichealth.service.*;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import javax.servlet.http.HttpServletRequest;

@RestController @RequestMapping("/v1/public-health")
public class PublicHealthController {
    private final PublicHealthValidator validator;
    private final PublicHealthGenerationService generations;
    private final ObjectMapper mapper;
    public PublicHealthController(PublicHealthValidator validator, PublicHealthGenerationService generations, ObjectMapper mapper) {
        this.validator = validator;
        this.generations = generations;
        this.mapper = mapper;
    }
    @PostMapping(value = "/generations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> generate(@RequestBody byte[] body,
    HttpServletRequest http) {
        PublicHealthValidator.Parsed parsed = validator.parse(body);
        http.setAttribute("publicHealth.requestId", parsed.request.getRequestId());
        http.setAttribute("publicHealth.originRequestId", parsed.request.getOriginRequestId());
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control","no-cache").header("X-Accel-Buffering","no").body(generations.generate(DeviceContextHolder.get(),
        parsed));
    }
    @ExceptionHandler(PublicHealthException.class)
    public ResponseEntity<ObjectNode> error(PublicHealthException error, HttpServletRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("schemaVersion","public-health.v1");
        body.put("code", error.getCode());
        body.put("message", error.getMessage());
        body.put("timestamp", System.currentTimeMillis());
        Object id = request.getAttribute("publicHealth.requestId"), origin = request.getAttribute("publicHealth.originRequestId");
        if (id != null) {
            body.put("requestId", id.toString());
        }
        if (origin != null) {
            body.put("originRequestId", origin.toString());
        }
        if (!error.getMissingFields().isEmpty()) {
            body.set("missingFields", mapper.valueToTree(error.getMissingFields()));
        }
        return ResponseEntity.status(error.getStatus()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
