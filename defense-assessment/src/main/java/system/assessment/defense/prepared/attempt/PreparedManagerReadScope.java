package system.assessment.defense.prepared.attempt;

import system.assessment.defense.application.service.ResourceAccessControlService;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.jobs.PreparedJobInput;

/** Internal teacher-only read scope. Historical ownership is the immutable record, not a
 * present-day student role/assignment. Never use this boundary to authorize a writer. */
final class PreparedManagerReadScope {
    private PreparedManagerReadScope() {}
    static PreparedAttemptStart.Summary attempt(ResourceAccessControlService access,PreparedAttemptStartRepository attempts,
            int actor,int student,int assessment,String id){
        if(actor<1||student<1||assessment<1)throw new IllegalArgumentException("历史评阅身份或考核无效");PreparedJobInput.uuid(id);
        access.requireAssessmentManager(actor,assessment);attempts.lockAssessment(assessment);
        if(!attempts.managerReadAllowed(actor,student,assessment,id))throw new BusinessException(403,"无权限或原尝试历史不存在");
        return attempts.get(student,assessment,id);
    }
}
