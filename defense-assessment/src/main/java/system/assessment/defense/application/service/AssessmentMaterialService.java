package system.assessment.defense.application.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import system.assessment.defense.application.dto.FileDTO;
import system.assessment.defense.application.manage.OpenAiManager;
import system.assessment.defense.domain.event.SettingCreateOrUpdateEvent;
import system.assessment.defense.infrastructure.common.FileByteDTO;
import system.assessment.defense.infrastructure.common.HttpUtils;
import system.assessment.defense.infrastructure.repository.dao.po.AssessmentSettingPO;
import system.assessment.defense.infrastructure.repository.dao.service.AssessmentSettingService;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssessmentMaterialService {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private static final int MAX_CONTEXT_CHARS = 8000;
    private static final int MAX_MATERIAL_TEXT_CHARS = 1800;
    private static final int MIN_MATERIAL_TEXT_CHARS = 300;
    private static final int MAX_ASSESSMENT_FILES_JSON_CHARS = 3500;
    private static final int MAX_ERROR_CHARS = 500;

    private static final String MATERIAL_EXTRACT_SYSTEM_PROMPT = """
            你是一位专业的项目材料分析助手。请从 PDF 中提取后续面试出题和评分需要的事实信息。

            要求：
            1. 只基于 PDF 内容，不编造信息
            2. 使用简体中文
            3. 输出结构化文本，不要输出 JSON 或 Markdown 代码块
            4. 尽量覆盖：项目背景、目标、功能模块、系统架构、技术栈、关键实现、数据/模型/算法、测试验证、创新点、风险与不足
            5. 内容要紧凑，保留可用于出题和评分的细节
            """;

    private static final String MATERIAL_EXTRACT_USER_PROMPT = """
            请解析这份考试材料，生成后续面试出题和评分可直接使用的材料上下文。
            """;

    private final HttpUtils httpUtils;
    private final OpenAiManager openAiManager;
    private final AssessmentSettingService settingService;
    private final ThreadPoolExecutor threadPoolExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSettingCreateOrUpdate(SettingCreateOrUpdateEvent event) {
        AssessmentSettingPO setting = event.getSettingPO();
        if (setting == null || setting.getId() == null || !hasAssessmentFiles(setting)) {
            return;
        }
        parseAndCacheAsync(setting.getId());
    }

    public void parseAndCacheAsync(Integer assessmentId) {
        try {
            threadPoolExecutor.execute(() -> {
                try {
                    AssessmentSettingPO setting = settingService.getById(assessmentId);
                    if (setting != null) {
                        getOrParseMaterialContext(setting);
                    }
                } catch (Exception e) {
                    log.error("解析考试材料失败, assessmentId:{}, errorType:{}",
                            assessmentId, e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException exception) {
            log.warn("材料预解析队列已满，将在使用材料时重试, assessmentId:{}", assessmentId);
        }
    }

    public String getOrParseMaterialContext(AssessmentSettingPO setting) {
        if (setting == null || !hasAssessmentFiles(setting)) {
            return "";
        }

        List<FileDTO> files = parseFiles(setting.getAssessmentFiles());
        if (CollectionUtils.isEmpty(files)) {
            return "";
        }

        boolean changed = false;
        for (FileDTO file : files) {
            if (!needsParsing(file)) {
                continue;
            }
            parseFile(file);
            changed = true;
        }

        if (changed) {
            String assessmentFiles = serializeFilesForStorage(files);
            setting.setAssessmentFiles(assessmentFiles);
            try {
                settingService.update(new UpdateWrapper<AssessmentSettingPO>()
                        .set("assessment_files", assessmentFiles)
                        .eq("id", setting.getId()));
            } catch (Exception e) {
                log.warn("缓存考试材料失败，不阻断本次出题, assessmentId:{}, jsonLength:{}, errorType:{}",
                        setting.getId(), assessmentFiles.length(), e.getClass().getSimpleName());
            }
        }

        // A failed or malformed material must never be silently treated as an
        // empty knowledge base. Question generation can ask the user to retry,
        // while scoring releases its claim and is retried by the durable
        // analysis scheduler.
        if (files.stream().filter(Objects::nonNull)
                .anyMatch(file -> !Objects.equals(file.getMaterialStatus(), STATUS_READY))) {
            throw new IllegalStateException("考核材料尚未解析完成");
        }

        return buildMaterialContext(files);
    }

    private boolean hasAssessmentFiles(AssessmentSettingPO setting) {
        return StringUtils.isNotBlank(setting.getAssessmentFiles());
    }

    private List<FileDTO> parseFiles(String assessmentFiles) {
        if (StringUtils.isBlank(assessmentFiles)) {
            return Collections.emptyList();
        }
        return JSONUtil.parseArray(assessmentFiles).toList(FileDTO.class);
    }

    private boolean needsParsing(FileDTO file) {
        return file != null
                && StringUtils.isNotBlank(file.getFileUrl())
                && !Objects.equals(file.getMaterialStatus(), STATUS_READY);
    }

    private void parseFile(FileDTO file) {
        try {
            FileByteDTO fileByteDTO = httpUtils.downloadFile(file.getFileUrl());
            if (fileByteDTO == null || fileByteDTO.getBytes() == null || fileByteDTO.getBytes().length == 0) {
                throw new IllegalStateException("文件下载失败或内容为空");
            }

            String materialText = openAiManager.chatCompletionWithFiles(
                    openAiManager.getTextModel(),
                    MATERIAL_EXTRACT_SYSTEM_PROMPT,
                    MATERIAL_EXTRACT_USER_PROMPT,
                    List.of(new OpenAiManager.FileInput(fileByteDTO.getBytes(), "application/pdf"))
            );
            if (StringUtils.isBlank(materialText)) {
                throw new IllegalStateException("材料解析结果为空");
            }

            file.setMaterialText(StringUtils.abbreviate(materialText.trim(), MAX_MATERIAL_TEXT_CHARS));
            file.setMaterialStatus(STATUS_READY);
            file.setMaterialError(null);
            file.setMaterialParsedAt(System.currentTimeMillis());
        } catch (Exception e) {
            file.setMaterialStatus(STATUS_FAILED);
            file.setMaterialError(e.getClass().getSimpleName());
            file.setMaterialParsedAt(System.currentTimeMillis());
            log.error("解析考试材料文件失败, fileNameLength:{}, fileUrlLength:{}",
                    file.getFileName() == null ? 0 : file.getFileName().length(),
                    file.getFileUrl() == null ? 0 : file.getFileUrl().length());
        }
    }

    private String buildMaterialContext(List<FileDTO> files) {
        StringJoiner joiner = new StringJoiner("\n\n");
        for (FileDTO file : files) {
            if (file == null
                    || !Objects.equals(file.getMaterialStatus(), STATUS_READY)
                    || StringUtils.isBlank(file.getMaterialText())) {
                continue;
            }
            String fileName = StringUtils.defaultIfBlank(file.getFileName(), file.getFileUrl());
            joiner.add("【材料文件：" + fileName + "】\n" + file.getMaterialText().trim());
        }
        return StringUtils.abbreviate(joiner.toString(), MAX_CONTEXT_CHARS);
    }

    private String serializeFilesForStorage(List<FileDTO> files) {
        String json = JSONUtil.toJsonStr(files);
        int maxTextChars = MAX_MATERIAL_TEXT_CHARS;
        while (json.length() > MAX_ASSESSMENT_FILES_JSON_CHARS && maxTextChars > MIN_MATERIAL_TEXT_CHARS) {
            maxTextChars = Math.max(MIN_MATERIAL_TEXT_CHARS, maxTextChars - 300);
            for (FileDTO file : files) {
                if (file == null || !Objects.equals(file.getMaterialStatus(), STATUS_READY)) {
                    continue;
                }
                file.setMaterialText(StringUtils.abbreviate(file.getMaterialText(), maxTextChars));
            }
            json = JSONUtil.toJsonStr(files);
        }
        return json;
    }
}
