package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @USER taoHouChao
 * @DATE 09:52 2025/8/1
 */
@Data
@Schema(description = "WebSocket消息响应")
public class WebSocketResponse {

    @Schema(description = "响应内容")
    private String content;

    @Schema(description = "是否是正常消息")
    private Boolean normalMessage;

    @Schema(description = "是否是最后一条消息")
    private Boolean last;

    public static WebSocketResponse of(String content, Boolean normalMessage, Boolean last) {
        WebSocketResponse response = new WebSocketResponse();
        response.setContent(content);
        response.setNormalMessage(normalMessage);
        response.setLast(last);
        return response;
    }

    public static WebSocketResponse of(String content) {
        return of(content, true, false);
    }

    public static WebSocketResponse ofLast(String content) {
        return of(content, true, true);
    }

    public static WebSocketResponse ofError(String content) {
        return of(content, false, true);
    }
}
