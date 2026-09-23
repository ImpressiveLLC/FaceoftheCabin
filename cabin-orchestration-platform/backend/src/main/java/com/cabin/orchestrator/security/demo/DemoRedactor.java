package com.cabin.orchestrator.security.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * D22 R-DM-5 -- rewrites ALLOW_REDACT responses for demo-token callers only
 * (DemoAccessFilter sets the request attributes this keys off; every other
 * caller's response is returned untouched).
 *
 * For any JSON object that identifies a presence-class device (a
 * {@code deviceId}, or an {@code id} on a device row) it replaces the name
 * and every attribute value with {@link #HIDDEN_FOR_DEMO} and nulls every
 * {@code last*} timestamp, keeping deviceId, type, state and location so the
 * card and its online/offline badge render as they do today. Maps keyed by
 * a presence deviceId are redacted the same way (their values), and any
 * free text elsewhere that names a redacted device -- alert messages, rule
 * names, execution rows -- has that name replaced.
 */
@RestControllerAdvice
public class DemoRedactor implements ResponseBodyAdvice<Object> {

    public static final String HIDDEN_FOR_DEMO = "hidden for Demo viewers";

    private static final Logger log = LoggerFactory.getLogger("cabin.demo.access");

    private static final Set<String> NAME_FIELDS = Set.of("name", "friendlyName", "displayName", "displayLabel", "label", "deviceName");
    private static final Set<String> KEEP_FIELDS = Set.of("deviceId", "id", "type", "state", "location");

    private final DemoPresenceClassifier presence;
    private final ObjectMapper mapper;

    public DemoRedactor(DemoPresenceClassifier presence, ObjectMapper mapper) {
        this.presence = presence;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || !(request instanceof ServletServerHttpRequest servlet)) return body;
        Object routeClass = servlet.getServletRequest().getAttribute(DemoAccessFilter.REQUEST_ATTR_DEMO_ROUTE_CLASS);
        if (routeClass != DemoAccessPolicy.RouteClass.ALLOW_REDACT) return body;

        JsonNode tree = mapper.valueToTree(body);
        Map<String, String> hidden = presence.presenceDevices();
        int[] count = { 0 };
        JsonNode out = redact(tree, hidden, count);
        log.info("demo redaction token={} route={} redactedFields={}",
            servlet.getServletRequest().getAttribute(DemoAccessFilter.REQUEST_ATTR_DEMO_TOKEN_ID),
            servlet.getServletRequest().getRequestURI(), count[0]);
        return out;
    }

    /** Package-visible for DemoRedactorTest. */
    static JsonNode redact(JsonNode root, Map<String, String> hidden, int[] count) {
        if (hidden.isEmpty()) return root;
        // Longest names first so "Front Door Lock Battery" is replaced
        // before "Front Door Lock" would leave a dangling " Battery".
        List<String> names = new ArrayList<>();
        for (String n : hidden.values()) if (n != null && n.trim().length() >= 3) names.add(n);
        names.sort(Comparator.comparingInt(String::length).reversed());
        return walk(root, null, hidden, names, count);
    }

    private static JsonNode walk(JsonNode node, String fieldName, Map<String, String> hidden, List<String> names, int[] count) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (isPresenceRow(obj, hidden)) redactDeviceRow(obj, count);
            List<String> keys = new ArrayList<>();
            obj.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode child = obj.get(key);
                // Map keyed by deviceId (reporting-relationships, checkin-*,
                // reported-fields): a presence device's value is redacted whole.
                if (hidden.containsKey(key)) {
                    obj.set(key, redactKeyedValue(child, count));
                    continue;
                }
                obj.set(key, walk(child, key, hidden, names, count));
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) arr.set(i, walk(arr.get(i), fieldName, hidden, names, count));
            return arr;
        }
        if (node.isTextual() && (fieldName == null || !KEEP_FIELDS.contains(fieldName))) {
            String text = node.asText();
            String replaced = text;
            for (String name : names) {
                if (replaced.contains(name)) replaced = replaced.replace(name, HIDDEN_FOR_DEMO);
            }
            if (!replaced.equals(text)) {
                count[0]++;
                return TextNode.valueOf(replaced);
            }
        }
        return node;
    }

    private static boolean isPresenceRow(ObjectNode obj, Map<String, String> hidden) {
        JsonNode id = obj.get("deviceId");
        if (id == null && obj.has("id") && (obj.has("state") || obj.has("lastSeen") || obj.has("attributes"))) id = obj.get("id");
        return id != null && id.isTextual() && hidden.containsKey(id.asText());
    }

    private static void redactDeviceRow(ObjectNode obj, int[] count) {
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            if (NAME_FIELDS.contains(key) && obj.get(key).isTextual()) {
                obj.put(key, HIDDEN_FOR_DEMO);
                count[0]++;
            } else if (key.startsWith("last") && !obj.get(key).isNull()) {
                obj.putNull(key);
                count[0]++;
            } else if (key.equals("attributes") && obj.get(key).isObject()) {
                ObjectNode attrs = (ObjectNode) obj.get(key);
                Iterator<String> it = attrs.fieldNames();
                List<String> attrKeys = new ArrayList<>();
                it.forEachRemaining(attrKeys::add);
                for (String a : attrKeys) attrs.put(a, HIDDEN_FOR_DEMO);
                count[0] += attrKeys.size();
            }
        }
    }

    private static JsonNode redactKeyedValue(JsonNode value, int[] count) {
        if (value.isArray()) {
            // reported-fields: device -> field names. An empty list means the
            // history panel offers nothing to chart for this device.
            ArrayNode arr = (ArrayNode) value;
            boolean scalars = true;
            for (JsonNode n : arr) if (n.isContainerNode()) { scalars = false; break; }
            if (scalars) {
                count[0] += arr.size();
                arr.removeAll();
                return arr;
            }
            for (JsonNode n : arr) if (n.isObject()) redactDeviceRow((ObjectNode) n, count);
            return arr;
        }
        if (value.isObject()) {
            redactDeviceRow((ObjectNode) value, count);
            return value;
        }
        if (value.isTextual()) {
            // e.g. checkin-status: deviceId -> "ok"/"late" -- a status, kept.
            return value;
        }
        return value;
    }
}
