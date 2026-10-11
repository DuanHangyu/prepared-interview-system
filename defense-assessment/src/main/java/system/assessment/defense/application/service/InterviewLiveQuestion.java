package system.assessment.defense.application.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InterviewLiveQuestion {
    private String questionDimension;
    private String question;
    private boolean followGenerated;
    private String followQuestion;
}
