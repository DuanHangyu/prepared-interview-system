package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.exception.NotRoleException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionTest {
    @RestController static class ReadOnlyEvidence {
        @GetMapping("/readonly-evidence") public String read(){return "read-only";}
    }
    @Test void unsupportedWriteMethodIsNotMisclassifiedAsRetryableServerFailure()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new ReadOnlyEvidence()).setControllerAdvice(new GlobalException()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/readonly-evidence"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(405))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Allow",org.hamcrest.Matchers.containsString("GET")));
    }
    @Test void unsupportedMethodWithoutKnownAllowedMethodsHasSafeMessageAndNoInventedAllowHeader(){
        var response=new GlobalException().handleUnsupportedMethod(new HttpRequestMethodNotSupportedException("POST"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED,response.getStatusCode());assertEquals(405,response.getBody().getCode());
        assertEquals("请求方法不支持",response.getBody().getMessage());assertEquals(null,response.getHeaders().getFirst("Allow"));
    }
    @Test void expiryReasonIsStructuredAndOtherConflictsRemainUnclassified(){
        var response=new GlobalException().handleUploadWindowExpired(new system.assessment.defense.prepared.attempt.PreparedUploadWindow.Expired());
        assertEquals(HttpStatus.CONFLICT,response.getStatusCode());assertEquals(409,response.getBody().getCode());assertEquals("UPLOAD_WINDOW_EXPIRED",response.getBody().getData().reasonCode());
        var other=new GlobalException().handleException(new system.assessment.defense.infrastructure.exception.BusinessException(409,"原内容冲突"));assertEquals(null,other.getBody().getData());
    }
    @Test void authorizedMissingOriginalUploadKeepsNotFoundHttpStatusForExactRequestRecovery(){
        var response=new GlobalException().handleException(new system.assessment.defense.infrastructure.exception.BusinessException(404,"本回答尚无此原批次登记"));
        assertEquals(HttpStatus.NOT_FOUND,response.getStatusCode());assertEquals(404,response.getBody().getCode());
    }
    @Test void retiredOriginalAudioKeepsGoneStatusWithoutBecomingAnOrdinaryBadRequest(){
        var response=new GlobalException().handleException(new system.assessment.defense.infrastructure.exception.BusinessException(410,"原录音已按留存规则退出"));
        assertEquals(HttpStatus.GONE,response.getStatusCode());assertEquals(410,response.getBody().getCode());
        assertEquals("原录音已按留存规则退出",response.getBody().getMessage());assertEquals(null,response.getBody().getData());
        var pending=new GlobalException().handleException(new system.assessment.defense.infrastructure.exception.BusinessException(409,"原录音正在执行留存清理"));
        assertEquals(HttpStatus.CONFLICT,pending.getStatusCode());assertEquals(409,pending.getBody().getCode());
    }

    @Test
    void unavailablePreparedIntegrationKeepsServiceUnavailableHttpStatus() {
        var response = new GlobalException().handleException(new system.assessment.defense.infrastructure.exception.BusinessException(
                503, "AI 备题配置尚未核验，可继续手工备题"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getBody().getCode());
    }

    @Test
    void handleDataAccessResourceFailureReturnsActionableMessage() {
        GlobalException globalException = new GlobalException();

        ResponseEntity<BaseResult<Void>> response = globalException.handleDataAccessResourceFailure(
                new DataAccessResourceFailureException("connection refused"));
        BaseResult<Void> result = response.getBody();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(500, result.getCode());
        assertEquals("数据库连接失败，请检查本地数据库隧道或 DB_JDBC_URL 配置", result.getMessage());
    }

    @Test
    void handleExceptionUnwrapsNestedDataAccessResourceFailure() {
        GlobalException globalException = new GlobalException();

        ResponseEntity<BaseResult<Void>> response = globalException.handleException(new RuntimeException(
                "wrapped",
                new DataAccessResourceFailureException("connection refused")));
        BaseResult<Void> result = response.getBody();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(500, result.getCode());
        assertEquals("数据库连接失败，请检查本地数据库隧道或 DB_JDBC_URL 配置", result.getMessage());
    }

    @Test
    void roleMismatchReturnsExplicitForbiddenResult() {
        GlobalException globalException = new GlobalException();

        ResponseEntity<BaseResult<Void>> response = globalException.handleAuthorizationException(
                new NotRoleException("STUDENT", "login"));
        BaseResult<Void> result = response.getBody();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(403, result.getCode());
        assertEquals("无权限", result.getMessage());
    }

    @Test
    void missingStaticResourceReturnsNotFoundInsteadOfServerError() {
        GlobalException globalException = new GlobalException();

        ResponseEntity<BaseResult<Void>> response = globalException.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/v3/api-docs"));
        BaseResult<Void> result = response.getBody();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, result.getCode());
        assertEquals("访问资源不存在", result.getMessage());
    }
}
