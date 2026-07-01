package com.tsd.sano.es.core.result;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.tsd.sano.es.core.constant.ResultCode;
import com.tsd.sano.es.core.exception.ServiceException;
import org.slf4j.MDC;

import java.io.Serializable;

/**
 * 返回类的基础参数
 *
 * @author tnnn
 * @version V1.0
 * @date 2023-03-19 14:13
 */
public class ResultBasics implements Serializable {
    /**
     * 返回结果状态码
     */
    private Integer code;

    /**
     * 返回消息
     */
    private String message;

    /**
     * 时间戳
     */
    private Long ts;

    /**
     * traceId
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String traceId;

    public ResultBasics() {
    }

    public ResultBasics(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    public ResultBasics(Integer code, String message, String traceId) {
        this.code = code;
        this.message = message;
        this.traceId = traceId;
    }

    /**
     * 自动转换success的返回值：true,false
     */
    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getTs() {
        return System.currentTimeMillis();
    }


    /**
     * 获取链路追踪 ID。
     * <p>
     * 优先返回手动设置的值；未设置时从 SLF4J MDC 中读取（key: {@code traceId}），
     * </p>
     *
     * @return traceId，无法获取时为 null
     */
    public String getTraceId() {
        if (traceId != null) {
            return traceId;
        }
        return MDC.get("traceId");
    }


    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    @Override
    public String toString() {
        return "ResultCommon{" +
                "code=" + code +
                ", message='" + message + '\'' +
                ", ts=" + ts +
                ", traceId='" + traceId + '\'' +
                '}';
    }


    /**
     * 判断是否有异常。如果有，则抛出 {@link ServiceException} 异常
     */
    public void checkError() throws ServiceException {
        if (isSuccess()) {
            return;
        }
        // 业务异常
        throw new ServiceException(code, message);
    }

}
