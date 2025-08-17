package cn.com.vortexa.agent_hub.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author helei
 * @since 2025-08-16
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class QuestionAnswer {
    private String quizId;
    private String questionId;
    private String answerId;
}
