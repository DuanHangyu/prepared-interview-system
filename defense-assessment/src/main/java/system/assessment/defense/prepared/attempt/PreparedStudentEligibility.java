package system.assessment.defense.prepared.attempt;

/** Read-only preflight eligibility. READY_FOR_PREFLIGHT is not an authorization to see questions or record. */
public final class PreparedStudentEligibility {
    private PreparedStudentEligibility() {}
    public enum Phase {
        PAPER_NOT_PUBLISHED("教师尚未发布齐备试卷，请等待教师准备完成"),
        APPOINTMENT_REQUIRED("请先完成本考核的有效预约"),
        BEFORE_APPOINTMENT("尚未到预约开考时间，请按预约时间进入"),
        APPOINTMENT_WINDOW_EXPIRED("已超过首次开考窗口，请联系教师核验；此检查不会自动判缺考或计零分"),
        APPOINTMENT_STATE_CONFLICT("预约已不处于可首次开考状态，请联系教师核验，不要重复创建考试"),
        HISTORY_REQUIRES_REVIEW("已有作答记录，请核验原记录或原尝试；此检查不会覆盖记录或授权重答"),
        READY_FOR_PREFLIGHT("已满足试卷与预约条件，请先完成麦克风和本地保存预检；尚未授权正式开考");
        private final String message;
        Phase(String message) { this.message=message; }
        public String message() { return message; }
    }
    public record Status(int assessmentId, String theme, int questionCount, boolean publicationReady,
                         boolean appointmentRequired, Phase phase, String message, String serverTime,
                         String appointmentStartsAt, String latestFirstStartAt) {}
    /** Server-selected local-storage owner; never accepts identity or deployment from a client. */
    public record PreflightContext(int studentId, String deploymentId, Status eligibility, boolean workflowEnabled) {
        public PreflightContext(int studentId, String deploymentId, Status eligibility) {
            this(studentId, deploymentId, eligibility, false);
        }
        public PreflightContext {
            if(studentId<1 || deploymentId==null || !deploymentId.matches("prepared-[a-z0-9][a-z0-9-]{0,70}") || eligibility==null)
                throw new IllegalStateException("预检身份或独立部署配置不可用");
        }
    }
}
