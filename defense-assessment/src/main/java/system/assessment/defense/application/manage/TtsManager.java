package system.assessment.defense.application.manage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import system.assessment.defense.application.dto.OmniAudioResponseDTO;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * @USER taoHouChao
 * @DATE 18:48 2025/8/21
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TtsManager {

    private static final String QUESTION_VOICE_SYSTEM_PROMPT = """
            你是面试现场的语音合成器。
            你的唯一任务是把用户提供的题目文本原样转换成语音。
            禁止回答题目，禁止解释题目，禁止扩写、改写或补充任何内容。
            输出文本和语音内容必须只包含题目文本本身。
            语速自然，语气清晰、专业、平稳，中文播报速度适中。
            """ + AiPromptBoundary.SYSTEM_GUARD;

    private final OpenAiManager openAiManager;
    private final RealtimeVoiceOutputManager realtimeVoiceOutputManager;

    @Value("${dashscope.models.omni}")
    private String omniModel;

    @Value("${dashscope.audio.voice:Cherry}")
    private String voice;

    @Value("${dashscope.audio.format:wav}")
    private String format;

    private String buildQuestionVoicePrompt(String text) {
        return """
                请把下面的题目文本原样朗读成语音。
                输出内容只能是题目文本本身，禁止回答、解释、扩写或改写。

                %s
                """.formatted(AiPromptBoundary.wrapUntrustedData(Map.of(
                        "questionText", StringUtils.defaultString(text))));
    }

    public String generateVoice(String text) {
        log.info("tts request, textLength:{}", text == null ? 0 : text.length());
        StopWatch stopWatch = new StopWatch("generateVoice");
        stopWatch.start();
        try {
            OmniAudioResponseDTO audioResponse = openAiManager.chatCompletionWithAudioOutput(
                    omniModel,
                    QUESTION_VOICE_SYSTEM_PROMPT,
                    buildQuestionVoicePrompt(text),
                    voice,
                    format
            );
            if (StringUtils.isBlank(audioResponse.getAudioBase64())) {
                log.warn("omni tts response has no audio, textLength:{}",
                        audioResponse.getText() == null ? 0 : audioResponse.getText().length());
                return null;
            }
            return Base64.getEncoder().encodeToString(audioResponse.getAudioBytes());
        } catch (Exception e) {
            log.error("tts error, errorType:{}", e.getClass().getSimpleName());
            return null;
        } finally {
            stopWatch.stop();
            log.info("tts consumer time:{}", stopWatch.getTime());
        }
    }

    public boolean streamVoice(String text, Consumer<byte[]> audioChunkConsumer) {
        log.info("tts stream request, textLength:{}", text == null ? 0 : text.length());
        StopWatch stopWatch = new StopWatch("streamVoice");
        stopWatch.start();
        AtomicBoolean hasAudio = new AtomicBoolean(false);
        try {
            boolean realtimeHasAudio = realtimeVoiceOutputManager.streamQuestionVoice(text, bytes -> {
                hasAudio.set(true);
                audioChunkConsumer.accept(bytes);
            });
            if (realtimeHasAudio) {
                log.info("tts stream used realtime voice output");
                return true;
            }

            OmniAudioResponseDTO audioResponse = openAiManager.chatCompletionWithAudioOutput(
                    omniModel,
                    QUESTION_VOICE_SYSTEM_PROMPT,
                    buildQuestionVoicePrompt(text),
                    voice,
                    format,
                    bytes -> {
                        hasAudio.set(true);
                        audioChunkConsumer.accept(bytes);
                    }
            );
            if (!hasAudio.get() && StringUtils.isBlank(audioResponse.getAudioBase64())) {
                log.warn("omni tts stream response has no audio, textLength:{}",
                        audioResponse.getText() == null ? 0 : audioResponse.getText().length());
                return false;
            }
            return hasAudio.get() || StringUtils.isNotBlank(audioResponse.getAudioBase64());
        } catch (Exception e) {
            log.error("tts stream error, errorType:{}", e.getClass().getSimpleName());
            return false;
        } finally {
            stopWatch.stop();
            log.info("tts stream consumer time:{}", stopWatch.getTime());
        }
    }
}
