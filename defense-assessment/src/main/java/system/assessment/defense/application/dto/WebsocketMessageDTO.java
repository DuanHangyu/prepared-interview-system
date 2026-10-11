package system.assessment.defense.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import system.assessment.defense.infrastructure.emuns.WebsocketActionEnums;

import java.util.Objects;

/**
 * @USER taoHouChao
 * @DATE 22:25 2025/8/14
 */
@Data
@Schema(description = "websocket消息")
public class WebsocketMessageDTO {

    @Schema(description = "消息")
    private String message;

    @Schema(description = "操作")
    private String action;

    @Schema(description = "音频轮次 ID；开始/结束答题或答辩时必填")
    private String turnId;

    public boolean startDefense(){
        return Objects.equals(WebsocketActionEnums.START_DEFENSE.getAction(), action);
    }

    public boolean endDefense(){
        return Objects.equals(WebsocketActionEnums.END_DEFENSE.getAction(), action);
    }

    public boolean generateQuestion(){
        return Objects.equals(WebsocketActionEnums.GENERATE_QUESTION.getAction(), action);
    }

    public boolean startAssessment(){
        return Objects.equals(WebsocketActionEnums.START_ASSESSMENT.getAction(), action);
    }

    public boolean endAssessment(){
        return Objects.equals(WebsocketActionEnums.END_ASSESSMENT.getAction(), action);
    }

    public boolean startAnswer(){
        return Objects.equals(WebsocketActionEnums.START_ANSWER.getAction(), action);
    }

    public boolean endAnswer(){
        return Objects.equals(WebsocketActionEnums.END_ANSWER.getAction(), action);
    }

    public boolean stop(){
        return Objects.equals(WebsocketActionEnums.STOP.getAction(), action);
    }
}
