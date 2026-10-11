package system.assessment.defense.infrastructure.repository.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @USER taoHouChao
 * @DATE 19:59 2025/8/15
 */
@Data
@TableName("student_assessment_question_answer")
@Schema(description = "学生考核问题答案")
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StudentAssessmentQuestionAnswerPO {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private Integer assessmentId;

    private Integer studentId;

    private String question;

    private String answer;

    private String answerVoice;

    private String followQuestion;

    private String followAnswer;

    private String followAnswerVoice;

    private LocalDateTime createTime;
}
