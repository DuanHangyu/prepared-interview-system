package system.assessment.defense.prepared.attempt;

import system.assessment.defense.infrastructure.exception.BusinessException;

/** Read-only student projection. Approval is upload permission, never recording or media success. */
public final class PreparedUploadWindow {
    private PreparedUploadWindow() {}
    public record View(int studentId,int assessmentId,String attemptId,String answerId,String paperVersionId,
            String manifestSha256,String answerDeadline,String answeringEndedAt,String automaticDeadline,
            String serverNow,String phase,boolean mayUploadOriginal,long approvalRevision,String approvedAt,String uploadDeadline) {}
    public record Failure(String reasonCode) {}
    /** Only actual window rejection carries this marker. Original-content/client conflicts must not. */
    public static final class Expired extends BusinessException {
        public Expired(){super(409,"原回答补传窗口已结束，请保留原内容并联系教师审批");}
    }
}
