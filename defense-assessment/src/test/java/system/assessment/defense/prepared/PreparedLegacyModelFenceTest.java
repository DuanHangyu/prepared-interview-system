package system.assessment.defense.prepared;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import system.assessment.defense.application.manage.OpenAiManager;
import system.assessment.defense.application.service.StudentInterviewSessionService;
import system.assessment.defense.application.service.StudentAssessmentAnalysisService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.infrastructure.repository.dao.mapper.TokenConsumerMapper;
import system.assessment.defense.prepared.config.PreparedLegacyModelFence;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PreparedLegacyModelFenceTest {
    @Test void oldWebSocketAndAnalysisAreRejectedBeforeTheyCanChangeRecordsOrBufferAudio() throws Exception {
        var session=mock(StudentInterviewSessionService.class);var factory=new AspectJProxyFactory(session);factory.setProxyTargetClass(true);factory.addAspect(new PreparedLegacyModelFence());
        StudentInterviewSessionService proxy=factory.getProxy();
        assertThatThrownBy(()->proxy.handleWebsocketMessage(1,null,null)).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(()->proxy.appendVoice(java.nio.ByteBuffer.wrap(new byte[]{1}),1)).isInstanceOf(BusinessException.class);
        proxy.releaseAudio(1);verify(session).releaseAudio(1);verifyNoMoreInteractions(session);
        var analysis=mock(StudentAssessmentAnalysisService.class);factory=new AspectJProxyFactory(analysis);factory.setProxyTargetClass(true);factory.addAspect(new PreparedLegacyModelFence());
        StudentAssessmentAnalysisService analysisProxy=factory.getProxy();
        assertThatThrownBy(()->analysisProxy.reanalyze(1,9)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analysisProxy.submitClaimedAnalysis(9,1,null,null,2,false)).isInstanceOf(BusinessException.class);verifyNoInteractions(analysis);
    }
    @Test void legacyTextAudioAndFilePathsCannotBypassThePreparedBudgetWorkflow() {
        var http = mock(OkHttpClient.class);
        var mapper = mock(TokenConsumerMapper.class);
        var executor = mock(ThreadPoolExecutor.class);
        var manager = new OpenAiManager(http, new ObjectMapper(), mapper, executor);
        var factory = new AspectJProxyFactory(manager);
        factory.setProxyTargetClass(true);
        factory.addAspect(new PreparedLegacyModelFence());
        OpenAiManager proxy = factory.getProxy();
        assertThatThrownBy(() -> proxy.chatCompletion("unverified-model", "system", "input"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(409);
        assertThatThrownBy(() -> proxy.uploadFile(new byte[]{1}, "test.pdf", "file-extract"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> proxy.chatCompletionWithAudio("unverified-model", "system", new byte[]{1}, "wav", "input"))
                .isInstanceOf(BusinessException.class);
        proxy.getTextModel();
        verifyNoInteractions(http, mapper, executor);
    }
}
