package system.assessment.defense.application.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentQuestionAnswerPO;
import system.assessment.defense.infrastructure.repository.dao.po.StudentAssessmentRecordPO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ManagedObjectCleanupService {

    private final OssManager ossManager;
    private final ManagedObjectCleanupTaskRepository cleanupTaskRepository;

    public ManagedObjectCleanupService(OssManager ossManager) {
        this(ossManager, null);
    }

    @Autowired
    public ManagedObjectCleanupService(OssManager ossManager,
                                       ManagedObjectCleanupTaskRepository cleanupTaskRepository) {
        this.ossManager = ossManager;
        this.cleanupTaskRepository = cleanupTaskRepository;
    }

    public void deleteInterviewArtifacts(Collection<StudentAssessmentRecordPO> records,
                                         Collection<StudentAssessmentQuestionAnswerPO> answers) {
        List<String> references = new ArrayList<>();
        for (StudentAssessmentRecordPO record : records) {
            references.addAll(extractFileReferences(record.getDefenseFile()));
            references.add(record.getDefenseVoice());
        }
        for (StudentAssessmentQuestionAnswerPO answer : answers) {
            references.add(answer.getAnswerVoice());
            references.add(answer.getFollowAnswerVoice());
        }
        queueReferences(references);
    }

    public void queueRemovedReferences(Collection<String> previousReferences,
                                       Collection<String> retainedReferences) {
        Set<String> removedReferences = normalizedReferences(previousReferences);
        removedReferences.removeAll(normalizedReferences(retainedReferences));
        queueReferences(removedReferences);
    }

    public void queueReferences(Collection<String> references) {
        Set<String> uniqueReferences = normalizedReferences(references);
        if (uniqueReferences.isEmpty()) {
            return;
        }
        if (cleanupTaskRepository == null) {
            throw new IllegalStateException("Durable object cleanup repository is required");
        }
        cleanupTaskRepository.enqueue(uniqueReferences);
    }

    public void deleteAll(Collection<String> references) {
        for (String reference : normalizedReferences(references)) {
            ossManager.deleteStoredObject(reference);
        }
    }

    private Set<String> normalizedReferences(Collection<String> references) {
        Set<String> uniqueReferences = new LinkedHashSet<>();
        for (String reference : references) {
            if (reference != null && !reference.isBlank()) {
                uniqueReferences.add(reference);
            }
        }
        return uniqueReferences;
    }

    public List<String> extractFileReferences(String jsonOrReference) {
        if (jsonOrReference == null || jsonOrReference.isBlank()) {
            return List.of();
        }
        String value = jsonOrReference.trim();
        if (!value.startsWith("{") && !value.startsWith("[")) {
            return List.of(value);
        }
        List<String> references = new ArrayList<>();
        if (value.startsWith("[")) {
            JSONArray files = JSONUtil.parseArray(value);
            for (Object item : files) {
                collectFileUrl(JSONUtil.parseObj(item), references);
            }
        } else {
            collectFileUrl(JSONUtil.parseObj(value), references);
        }
        return references;
    }

    private void collectFileUrl(JSONObject file, List<String> references) {
        String fileUrl = file.getStr("fileUrl");
        if (fileUrl != null && !fileUrl.isBlank()) {
            references.add(fileUrl);
        }
    }
}
