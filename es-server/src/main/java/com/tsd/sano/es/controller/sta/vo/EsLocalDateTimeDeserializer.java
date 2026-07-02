package com.tsd.sano.es.controller.sta.vo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * ES日期字段反序列化器，兼容T分隔和空格分隔两种格式。
 */
public class EsLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    /**
     * 接口常用日期时间格式。
     */
    private static final DateTimeFormatter NORMAL_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        if (StringUtils.isBlank(value)) {
            return null;
        }

        String text = value.trim();
        try {
            // ES date常见返回格式，例如：2026-07-01T22:20:01。
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                // 兼容带时区格式，例如：2026-07-01T22:20:01Z。
                return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
            } catch (DateTimeParseException ignoredAgain) {
                // 兼容接口常用格式，例如：2026-07-01 22:20:01。
                return LocalDateTime.parse(text, NORMAL_FORMATTER);
            }
        }
    }
}
