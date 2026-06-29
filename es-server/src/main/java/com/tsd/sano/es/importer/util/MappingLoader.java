package com.tsd.sano.es.importer.util;

import com.tsd.sano.es.core.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ES Mapping加载器
 * <p>
 * 功能：
 * <p>
 * 1、加载resources/esmapping目录Mapping
 * <p>
 * 2、自动缓存Mapping
 * <p>
 * 3、避免重复IO
 * <p>
 * 4、统一管理Mapping
 * <p>
 * 目录：
 * <p>
 * resources
 * └── esmapping
 * wallet_record.json
 * coin_record.json
 * diamond_record.json
 *
 * @author lxw
 */
@Component
public class MappingLoader {

    private static final Logger log = LoggerFactory.getLogger(MappingLoader.class);

    /**
     * Mapping缓存
     * <p>
     * key：
     * wallet_record.json
     * <p>
     * value：
     * byte[]
     */
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /**
     * 获取Mapping输入流
     * <p>
     * 优先读取缓存
     *
     * @param mappingFile Mapping文件名
     * @return InputStream
     */
    public InputStream load(String mappingFile) {
        try {
            byte[] bytes = cache.computeIfAbsent(
                    mappingFile,
                    this::readBytes
            );
            return new java.io.ByteArrayInputStream(bytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("ES Mapping不存在：" + mappingFile, e);
        }

    }

    /**
     * Mapping是否存在
     */
    public boolean exists(String mappingFile) {

        try {
            ClassPathResource resource = new ClassPathResource("esmapping/" + mappingFile);
            return resource.exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清除缓存
     */
    public void clear() {
        cache.clear();
    }

    /**
     * 清除指定Mapping缓存
     */
    public void clear(String mappingFile) {
        cache.remove(mappingFile);
    }

    /**
     * 当前缓存数量
     */
    public int cacheSize() {
        return cache.size();

    }

    /**
     * 读取Mapping文件
     */
    private byte[] readBytes(String mappingFile) {
        try {
            log.info("===> ES-Import 加载Mapping：{}", mappingFile);
            ClassPathResource resource = new ClassPathResource("esmapping/" + mappingFile);
            if (!resource.exists()) {
                throw new BusinessException("Mapping不存在：" + mappingFile);
            }
            try (InputStream input = resource.getInputStream()) {
                return input.readAllBytes();
            }
        } catch (IOException e) {
            throw new RuntimeException("读取Mapping失败：" + mappingFile, e);
        }
    }

}