package com.tsd.sano.es.core.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * LocalDateTime序列化器，接口统一返回空格分隔格式。
 */
public class SpaceLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {

    /**
     * 接口输出日期时间格式。
     */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        generator.writeString(value == null ? null : value.format(FORMATTER));
    }
}
