package system.assessment.defense.infrastructure.exception;

import lombok.Getter;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;

/**
 * @USER taoHouChao
 * @DATE 10:06 2025/8/8
 */
@Getter
public class BusinessException extends RuntimeException{

    private final Integer code;
    private final String message;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.message = message;
    }

    public BusinessException(ErrorCodeEnums errorCodeEnums){
        super(errorCodeEnums.getMessage());
        this.code = errorCodeEnums.getCode();
        this.message = errorCodeEnums.getMessage();
    }

}
