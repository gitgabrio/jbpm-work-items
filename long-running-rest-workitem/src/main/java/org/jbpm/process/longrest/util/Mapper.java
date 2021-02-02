package org.jbpm.process.longrest.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Serializable;

public class Mapper implements Serializable {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static ObjectMapper getInstance() {
        return objectMapper;
    }

    public static String writeValueAsString(Object value) throws JsonProcessingException {
        return getInstance().writeValueAsString(value);
    }

    public static String writeValueAsString(Object value, boolean unescapeBefore) throws JsonProcessingException {
        if (unescapeBefore) {
            return getInstance().writeValueAsString(Json.unescape(value));
        } else {
            return getInstance().writeValueAsString(value);
        }
    }
}