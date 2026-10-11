package system.assessment.defense.infrastructure.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import system.assessment.defense.application.dto.LoginDTO;
import system.assessment.defense.infrastructure.emuns.ErrorCodeEnums;
import system.assessment.defense.infrastructure.exception.BusinessException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiHttpContractTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ContractController())
                .setControllerAdvice(new GlobalException(), new GlobalResponse())
                .addFilters(new NoStoreResponseFilter())
                .build();
    }

    @Test
    void wrapsSuccessfulResponses() throws Exception {
        mvc.perform(get("/contract/success"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        "no-store, no-cache, must-revalidate, max-age=0"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    void returnsRealAuthenticationAndAuthorizationStatuses() throws Exception {
        mvc.perform(get("/contract/not-login"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/contract/not-role"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/contract/not-permission"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/contract/business-no-permission"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权限"));
    }

    @Test
    void mapsConflictRateLimitAndPayloadTooLargeStatuses() throws Exception {
        mvc.perform(get("/contract/duplicate"))
                .andExpect(status().isConflict());
        mvc.perform(get("/contract/teacher-has-assessments"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(143));
        mvc.perform(get("/contract/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("数据仍被其他业务使用，无法执行当前操作"));
        mvc.perform(get("/contract/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
        mvc.perform(get("/contract/file-too-large"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(get("/contract/multipart-too-large"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void mapsDatabaseOutageToServiceUnavailableAndUnknownFailureToServerError() throws Exception {
        mvc.perform(get("/contract/database"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(500));
        mvc.perform(get("/contract/wrapped-database"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(500));
        mvc.perform(get("/contract/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统异常"));
    }

    @Test
    void rejectsInvalidAndMalformedJsonWithBadRequest() throws Exception {
        mvc.perform(post("/contract/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mvc.perform(get("/contract/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码至少需要8个字符"));

        mvc.perform(post("/contract/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @RestController
    @RequestMapping("/contract")
    static class ContractController {

        @GetMapping("/success")
        String success() {
            return "ok";
        }

        @PostMapping("/validated")
        String validated(@Valid @RequestBody LoginDTO ignored) {
            return "ok";
        }

        @GetMapping("/not-login")
        void notLogin() {
            throw new NotLoginException("未登录", NotLoginException.NOT_TOKEN, "login");
        }

        @GetMapping("/not-role")
        void notRole() {
            throw new NotRoleException("ADMIN", "login");
        }

        @GetMapping("/not-permission")
        void notPermission() {
            throw new NotPermissionException("student:read", "login");
        }

        @GetMapping("/business-no-permission")
        void businessNoPermission() {
            throw new BusinessException(ErrorCodeEnums.NO_PERMISSION);
        }

        @GetMapping("/duplicate")
        void duplicate() {
            throw new DuplicateKeyException("duplicate");
        }

        @GetMapping("/teacher-has-assessments")
        void teacherHasAssessments() {
            throw new BusinessException(143, "该教师仍有关联考核，无法删除");
        }

        @GetMapping("/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException("foreign key detail must not leak");
        }

        @GetMapping("/rate-limit")
        void rateLimit() {
            throw new BusinessException(ErrorCodeEnums.RATE_LIMIT_EXCEEDED);
        }

        @GetMapping("/file-too-large")
        void fileTooLarge() {
            throw new BusinessException(ErrorCodeEnums.FILE_TOO_LARGE);
        }

        @GetMapping("/multipart-too-large")
        void multipartTooLarge() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/database")
        void database() {
            throw new DataAccessResourceFailureException("connection refused");
        }

        @GetMapping("/wrapped-database")
        void wrappedDatabase() {
            throw new IllegalStateException("wrapped", new DataAccessResourceFailureException("connection refused"));
        }

        @GetMapping("/unknown")
        void unknown() {
            throw new IllegalStateException("sensitive internal detail");
        }

        @GetMapping("/illegal-argument")
        void illegalArgument() {
            throw new IllegalArgumentException("密码至少需要8个字符");
        }
    }
}
