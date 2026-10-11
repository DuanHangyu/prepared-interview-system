package system.assessment.defense.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @USER taoHouChao
 * @DATE 20:54 2025/8/15
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class QuestionDTO {

    private Integer index;

    private Integer total;

    private String title;

    private Boolean follow;

    private Boolean last;
}
