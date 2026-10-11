package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import java.util.Set;

/**
 * @Author taoHouChao
 * @Date 12:27 2025/6/7
 */
@RestControllerAdvice
@Slf4j
public class GlobalException {

    private static final String DATABASE_CONNECTION_ERROR =
            "数据库连接失败，请检查本地数据库隧道或 DB_JDBC_URL 配置";
    private static final Set<Integer> CONFLICT_CODES = Set.of(
            ErrorCodeEnums.USER_ALREADY_EXISTS.getCode(),
            ErrorCodeEnums.PASSWORD_SAME.getCode(),
            ErrorCodeEnums.IMPORT_STUDENT_EXIST.getCode(),
            ErrorCodeEnums.EXIST_STUDENT.getCode(),
            ErrorCodeEnums.ASSESSMENT_SETTING_APPOINTMENT_EXIST.getCode(),
            ErrorCodeEnums.TEACHER_HAS_ASSESSMENTS.getCode()
    );

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<BaseResult<Void>> handleException(DuplicateKeyException e){
        log.warn("duplicate key rejected, errorType:{}", e.getClass().getSimpleName());
        return response(HttpStatus.CONFLICT, BaseResult.error(600, "存在相同的数据"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<BaseResult<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("data integrity operation rejected, errorType:{}", e.getClass().getSimpleName());
        return response(HttpStatus.CONFLICT,
                BaseResult.error(HttpStatus.CONFLICT.value(), "数据仍被其他业务使用，无法执行当前操作"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResult<Void>> handleException(BusinessException e){
        log.warn("business request rejected, code:{}", e.getCode());
        return response(businessStatus(e.getCode()), BaseResult.error(e.getCode(), e.getMessage()));
    }
    @ExceptionHandler(system.assessment.defense.prepared.attempt.PreparedUploadWindow.Expired.class)
    public ResponseEntity<BaseResult<system.assessment.defense.prepared.attempt.PreparedUploadWindow.Failure>> handleUploadWindowExpired(
            system.assessment.defense.prepared.attempt.PreparedUploadWindow.Expired e){
        var result=BaseResult.<system.assessment.defense.prepared.attempt.PreparedUploadWindow.Failure>error(409,e.getMessage());
        result.setData(new system.assessment.defense.prepared.attempt.PreparedUploadWindow.Failure("UPLOAD_WINDOW_EXPIRED"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<BaseResult<Void>> handleException(NotLoginException e){
        log.warn("unauthenticated request rejected, errorType:{}", e.getClass().getSimpleName());
        return response(HttpStatus.UNAUTHORIZED, BaseResult.error(401, "未登录或登录已过期"));
    }

    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<BaseResult<Void>> handleAuthorizationException(RuntimeException e){
        log.warn("authorization request rejected, errorType:{}", e.getClass().getSimpleName());
        return response(HttpStatus.FORBIDDEN, BaseResult.error(403, "无权限"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<BaseResult<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return badRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResult<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse("请求参数校验失败");
        return badRequest(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<BaseResult<Void>> handleMethodValidation(HandlerMethodValidationException e) {
        return badRequest("请求参数校验失败");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class
    })
    public ResponseEntity<BaseResult<Void>> handleBadRequest(Exception e) {
        log.warn("invalid request rejected, errorType:{}", e.getClass().getSimpleName());
        return badRequest("请求参数格式错误");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<BaseResult<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("oversized upload rejected, errorType:{}", e.getClass().getSimpleName());
        return response(HttpStatus.PAYLOAD_TOO_LARGE,
                BaseResult.error(ErrorCodeEnums.FILE_TOO_LARGE.getCode(),
                        ErrorCodeEnums.FILE_TOO_LARGE.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<BaseResult<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("illegal argument rejected, errorType:{}", e.getClass().getSimpleName());
        return badRequest(e.getMessage() == null || e.getMessage().isBlank()
                ? "请求参数错误"
                : e.getMessage());
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<BaseResult<Void>> handleDataAccessResourceFailure(DataAccessResourceFailureException e){
        log.error("数据库连接异常, errorType:{}", e.getClass().getSimpleName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, BaseResult.error(500, DATABASE_CONNECTION_ERROR));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<BaseResult<Void>> handleNoResourceFound(NoResourceFoundException e) {
        log.info("resource not found, method:{}, pathLength:{}", e.getHttpMethod(), e.getResourcePath().length());
        return response(HttpStatus.NOT_FOUND, BaseResult.error(404, "访问资源不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<BaseResult<Void>> handleUnsupportedMethod(HttpRequestMethodNotSupportedException e) {
        log.info("unsupported request method rejected");
        var headers=new HttpHeaders();var allowed=e.getSupportedHttpMethods();
        if(allowed!=null)headers.setAllow(allowed);
        return new ResponseEntity<>(BaseResult.error(405,"请求方法不支持"),headers,HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResult<Void>> handleException(Exception e){
        log.error("unhandled request failure, errorType:{}", e.getClass().getSimpleName());
        // Lazy compatibility storage initialization can wrap the real SDK rejection.
        // Do not leak provider messages/credentials or call an unconfirmed file saved.
        if (hasCause(e, com.aliyun.oss.common.auth.InvalidCredentialsException.class)) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, BaseResult.error(503,
                    "文件存储配置不可用，请联系教师或管理员；未确认文件不能视为已保存"));
        }
        if (hasCause(e, DataAccessResourceFailureException.class)) {
            return response(HttpStatus.SERVICE_UNAVAILABLE, BaseResult.error(500, DATABASE_CONNECTION_ERROR));
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR, BaseResult.error(500, "系统异常"));
    }

    private HttpStatus businessStatus(Integer code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == HttpStatus.UNAUTHORIZED.value()) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == HttpStatus.NOT_FOUND.value()) {
            return HttpStatus.NOT_FOUND;
        }
        if (code == HttpStatus.GONE.value()) {
            return HttpStatus.GONE;
        }
        if (code == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (code == ErrorCodeEnums.NO_PERMISSION.getCode() || code == HttpStatus.FORBIDDEN.value()) {
            return HttpStatus.FORBIDDEN;
        }
        if (CONFLICT_CODES.contains(code) || code == HttpStatus.CONFLICT.value()) {
            return HttpStatus.CONFLICT;
        }
        if (code == ErrorCodeEnums.FILE_TOO_LARGE.getCode()
                || code == ErrorCodeEnums.VOICE_DATA_TOO_LARGE.getCode()
                || code == HttpStatus.PAYLOAD_TOO_LARGE.value()) {
            return HttpStatus.PAYLOAD_TOO_LARGE;
        }
        if (code == ErrorCodeEnums.RATE_LIMIT_EXCEEDED.getCode()) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private ResponseEntity<BaseResult<Void>> badRequest(String message) {
        return response(HttpStatus.BAD_REQUEST, BaseResult.error(400, message));
    }

    private ResponseEntity<BaseResult<Void>> response(HttpStatus status, BaseResult<Void> body) {
        return ResponseEntity.status(status).body(body);
    }

    private boolean hasCause(Throwable e, Class<? extends Throwable> causeType) {
        Throwable current = e;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
