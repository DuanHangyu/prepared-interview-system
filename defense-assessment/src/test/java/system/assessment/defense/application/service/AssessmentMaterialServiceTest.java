package system.assessment.defense.application.service;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import system.assessment.defense.application.dto.FileDTO;
import system.assessment.defense.application.manage.OpenAiManager;
import system.assessment.defense.infrastructure.common.FileByteDTO;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssessmentMaterialServiceTest {

    @Mock
    private HttpUtils httpUtils;

    @Mock
    private OpenAiManager openAiManager;

    @Mock
    private AssessmentSettingService settingService;

    private AssessmentMaterialService materialService;
    private ThreadPoolExecutor threadPoolExecutor;

    @BeforeEach
    void setUp() {
        threadPoolExecutor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        materialService = new AssessmentMaterialService(httpUtils, openAiManager, settingService, threadPoolExecutor);
    }

    @AfterEach
    void tearDown() {
        threadPoolExecutor.shutdownNow();
    }

    @Test
    void usesCachedMaterialContextWithoutDownloadingPdfAgain() {
        FileDTO file = new FileDTO();
        file.setFileName("design.pdf");
        file.setFileUrl("oss/design.pdf");
        file.setMaterialStatus(AssessmentMaterialService.STATUS_READY);
        file.setMaterialText("项目背景：学生完成了一个 AI 面试系统。");

        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(7)
                .assessmentFiles(JSONUtil.toJsonStr(List.of(file)))
                .build();

        String context = materialService.getOrParseMaterialContext(setting);

        assertThat(context).contains("design.pdf", "AI 面试系统");
        verifyNoInteractions(httpUtils, openAiManager, settingService);
    }

    @Test
    void parsesAndCachesMaterialContextWhenMissing() {
        FileDTO file = new FileDTO();
        file.setFileName("design.pdf");
        file.setFileUrl("oss/design.pdf");

        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(7)
                .assessmentFiles(JSONUtil.toJsonStr(List.of(file)))
                .build();

        byte[] pdfBytes = "fake pdf".getBytes(StandardCharsets.UTF_8);
        when(httpUtils.downloadFile("oss/design.pdf")).thenReturn(new FileByteDTO(pdfBytes));
        when(openAiManager.getTextModel()).thenReturn("qwen3.6-plus");
        when(openAiManager.chatCompletionWithFiles(eq("qwen3.6-plus"), anyString(), anyString(), any()))
                .thenReturn("项目背景：缓存后的材料摘要。");

        String context = materialService.getOrParseMaterialContext(setting);

        assertThat(context).contains("design.pdf", "缓存后的材料摘要");
        assertThat(setting.getAssessmentFiles()).contains(AssessmentMaterialService.STATUS_READY, "缓存后的材料摘要");
        verify(settingService).update(any());
    }

    @Test
    void returnsParsedContextWhenCacheUpdateFails() {
        FileDTO file = new FileDTO();
        file.setFileName("large-design.pdf");
        file.setFileUrl("oss/large-design.pdf");

        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(7)
                .assessmentFiles(JSONUtil.toJsonStr(List.of(file)))
                .build();

        when(httpUtils.downloadFile("oss/large-design.pdf"))
                .thenReturn(new FileByteDTO("fake pdf".getBytes(StandardCharsets.UTF_8)));
        when(openAiManager.getTextModel()).thenReturn("qwen3.6-plus");
        when(openAiManager.chatCompletionWithFiles(eq("qwen3.6-plus"), anyString(), anyString(), any()))
                .thenReturn("项目背景：".repeat(1000));
        when(settingService.update(any())).thenThrow(new RuntimeException("Data too long for column 'assessment_files'"));

        assertThatCode(() -> {
            String context = materialService.getOrParseMaterialContext(setting);

            assertThat(context).contains("large-design.pdf", "项目背景");
            assertThat(setting.getAssessmentFiles().length()).isLessThanOrEqualTo(3500);
        }).doesNotThrowAnyException();
    }

    @Test
    void returnsEmptyContextWhenThereAreNoAssessmentFiles() {
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(7)
                .assessmentFiles("")
                .build();

        String context = materialService.getOrParseMaterialContext(setting);

        assertThat(context).isEmpty();
        verify(httpUtils, never()).downloadFile(anyString());
        verifyNoInteractions(openAiManager, settingService);
    }

    @Test
    void saturatedPreparseQueueDoesNotTurnACommittedSettingSaveIntoAFailure() {
        ThreadPoolExecutor rejectingExecutor = org.mockito.Mockito.mock(ThreadPoolExecutor.class);
        org.mockito.Mockito.doThrow(new RejectedExecutionException("queue full"))
                .when(rejectingExecutor).execute(any(Runnable.class));
        AssessmentMaterialService service = new AssessmentMaterialService(
                httpUtils, openAiManager, settingService, rejectingExecutor);

        assertThatCode(() -> service.parseAndCacheAsync(7)).doesNotThrowAnyException();
    }

    @Test
    void parsingFailureCannotSilentlyBecomeAnEmptyScoringContext() {
        FileDTO file = new FileDTO();
        file.setFileName("unavailable.pdf");
        file.setFileUrl("oss/unavailable.pdf");
        AssessmentSettingPO setting = AssessmentSettingPO.builder()
                .id(7)
                .assessmentFiles(JSONUtil.toJsonStr(List.of(file)))
                .build();
        when(httpUtils.downloadFile("oss/unavailable.pdf"))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> materialService.getOrParseMaterialContext(setting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未解析完成");

        assertThat(setting.getAssessmentFiles()).contains(AssessmentMaterialService.STATUS_FAILED);
        verify(settingService).update(any());
        verifyNoInteractions(openAiManager);
    }
}
