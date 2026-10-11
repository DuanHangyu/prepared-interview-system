package system.assessment.defense.infrastructure.config;

import lombok.Data;

import java.io.Serializable;

/**
 * @Author taoHouChao
 * @Date 12:25 2025/6/7
 */
@Data
public class BaseResult<T> implements Serializable {

    private int code;

    private String message;

    private T data;

    public static <T> BaseResult<T> success(T data) {
        BaseResult<T> result = new BaseResult<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        return result;
    }

    public static <T> BaseResult<T> error(int code, String message) {
        BaseResult<T> result = new BaseResult<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
