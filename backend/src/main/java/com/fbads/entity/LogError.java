package com.fbads.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fbads.client.FbException;
import com.fbads.common.JsonConverters;

import java.util.Map;

/**
 * Nhật ký: lỗi gặp phải. Lỗi Facebook giữ nguyên mã (code, subcode, type, fbtraceId…) và yêu cầu đã gửi (request,
 * đã bỏ token); lỗi mạng có network/timeout/systemMessage; lỗi nội bộ kèm vài dòng stack.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogError(String message, Integer code, Integer subcode, String type, String fbtraceId, String userMsg,
        String rawMessage, Integer httpStatus, Boolean network, Map<String, Object> request, Boolean timeout,
        String systemMessage, String stack) {

    /** Lỗi chỉ có câu báo (vd kế hoạch không làm được, bị giới hạn số lần gọi) */
    public static LogError of(String message) {
        return new LogError(message, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** Lỗi bất kỳ → bản gọn để lưu nhật ký */
    public static LogError of(Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        Map<String, Object> fb = e instanceof FbException f ? f.fb() : Map.of();
        Integer code = num(fb.get("code")), httpStatus = num(fb.get("httpStatus"));
        Boolean network = (Boolean) fb.get("network");
        String stack = code == null && network == null && httpStatus == null ? stackOf(e) : null;
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) fb.get("request");
        return new LogError(message, code, num(fb.get("subcode")), text(fb.get("type")), text(fb.get("fbtraceId")),
                text(fb.get("userMsg")), text(fb.get("rawMessage")), httpStatus, network, request,
                (Boolean) fb.get("timeout"), text(fb.get("systemMessage")), stack);
    }

    /** Tên lỗi và tối đa 5 dòng đầu của stack, cắt còn 800 ký tự */
    private static String stackOf(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        StackTraceElement[] st = e.getStackTrace();
        for (int i = 0; i < Math.min(5, st.length); i++) sb.append("\n    at ").append(st[i]);
        return sb.length() > 800 ? sb.substring(0, 800) : sb.toString();
    }

    private static Integer num(Object o) { return o instanceof Number n ? n.intValue() : null; }

    private static String text(Object o) { return o == null ? null : o.toString(); }

    public static class Converter extends JsonConverters.Of<LogError> {
        public Converter() { super(LogError.class); }
    }
}
