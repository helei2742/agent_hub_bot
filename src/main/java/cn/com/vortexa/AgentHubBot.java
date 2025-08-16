package cn.com.vortexa;

import cn.com.vortexa.account.entity.ProxyInfo;
import cn.com.vortexa.base.constants.HeaderKey;
import cn.com.vortexa.bot_template.bot.AbstractVortexaBot;
import cn.com.vortexa.bot_template.bot.VortexaBotContext;
import cn.com.vortexa.bot_template.bot.anno.VortexaBot;
import cn.com.vortexa.bot_template.bot.anno.VortexaBotAPI;
import cn.com.vortexa.bot_template.bot.anno.VortexaBotCatalogueGroup;
import cn.com.vortexa.bot_template.bot.dto.FullAccountContext;
import cn.com.vortexa.bot_template.constants.BotAppConnectStatus;
import cn.com.vortexa.common.constants.HttpMethod;
import cn.com.vortexa.common.util.http.RestApiClientFactory;
import cn.com.vortexa.dto.QuestionAnswer;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * @author helei
 * @since 2025-08-16
 */
@Slf4j
@VortexaBot(
        catalogueGroup = {
                @VortexaBotCatalogueGroup(name = "一次性任务", order = 2),
        }
)
public class AgentHubBot extends AbstractVortexaBot {

    public static final String BASE_URL = "https://hub-api.agnthub.ai/api";

    private static final Map<String, Map<String, String>> answerMap;

    static {
        answerMap = new HashMap<>();
        try (InputStream is = new FileInputStream(
                System.getProperty("user.dir") + File.separator + "question-answer.json"
        )) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            List<QuestionAnswer> questionAnswers = JSONArray.parseArray(content, QuestionAnswer.class);
            for (QuestionAnswer questionAnswer : questionAnswers) {
                answerMap.computeIfAbsent(questionAnswer.getQuizId(), k -> new HashMap<>())
                        .computeIfAbsent(questionAnswer.getQuestionId(), k -> questionAnswer.getAnswerId());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public AgentHubBot(VortexaBotContext vortexaBotContext) {
        super(vortexaBotContext);
    }

    @VortexaBotAPI(
            name = "Learn & Earn-[view-task]",
            catalogueName = "一次性任务",
            catalogueOrder = 1,
            connectStatus = BotAppConnectStatus.OFFLINE
    )
    public void completeLearnAndEarnViewTask(ProxyInfo proxyInfo, String cookie) {
        FullAccountContext fullAccountContext = new FullAccountContext();
        fullAccountContext.setProxy(proxyInfo);
        fullAccountContext.putParam(HeaderKey.COOKIE, cookie);

        List<String> ids = null;
        try {
            ids = queryAccountAvailableViewTask(fullAccountContext);
            log.info("[Learn & Earn][View] account[{}] have available task: {}", fullAccountContext.getId(), ids.size());
        } catch (Exception e) {
            throw new RuntimeException("[Learn & Earn] query available task error", e);
        }

        if (CollUtil.isEmpty(ids)) {
            log.warn("[Learn & Earn][View] account[{}] no available task", fullAccountContext.getId());
        }

        int errorCount = 0;
        for (String id : ids) {
            try {
                log.info("[Learn & Earn][View] account[{}] start complete task: {}", fullAccountContext.getId(), id);
                completeViewTask(fullAccountContext, id);
                int sleep = RandomUtil.randomInt(500);
                log.info("[Learn & Earn][View] account[{}] complete task: {} success...continue after {} ms"
                        , fullAccountContext.getId(), id, sleep);
                TimeUnit.MILLISECONDS.sleep(sleep);
            } catch (Exception e) {
                errorCount++;
                log.error("[Learn & Earn][View] account[{}] complete task: {} error, {}", fullAccountContext.getId(), id, e.getMessage());
            }
        }
        log.info("[Learn & Earn][View] account[{}] task complete [{}/{}]"
                , fullAccountContext.getId(), ids.size() - errorCount, ids.size());
    }


    @VortexaBotAPI(
            name = "Learn & Earn-[QA-task]",
            catalogueName = "一次性任务",
            catalogueOrder = 1,
            connectStatus = BotAppConnectStatus.OFFLINE
    )
    public void completeLearnAndEarnQATask(ProxyInfo proxyInfo, String cookie) {
        FullAccountContext fullAccountContext = new FullAccountContext();
        fullAccountContext.setProxy(proxyInfo);
        fullAccountContext.putParam(HeaderKey.COOKIE, cookie);

        List<QuestionAnswer> questionAnswers = null;
        try {
            questionAnswers = queryAccountAvailableQATask(fullAccountContext);
            log.info("[Learn & Earn][QA] account[{}] have available QA task: {}",
                    fullAccountContext.getId(), questionAnswers.size()
            );
        } catch (Exception e) {
            throw new RuntimeException("[Learn & Earn] query available task error", e);
        }

        if (CollUtil.isEmpty(questionAnswers)) {
            log.warn("[Learn & Earn][QA] account[{}] no available QA task", fullAccountContext.getId());
        }

        Map<String, List<QuestionAnswer>> quizMap =
                questionAnswers.stream().collect(Collectors.groupingBy(QuestionAnswer::getQuizId));

        int errorCount = 0;
        for (Map.Entry<String, List<QuestionAnswer>> entry : quizMap.entrySet()) {
            String quizId = entry.getKey();
            try {
                startQuiz(fullAccountContext, quizId);
                log.info("[Learn & Earn][QA] account[{}] start quiz [{}] success",
                        fullAccountContext.getId(), quizId
                );
            } catch (ExecutionException | InterruptedException e) {
                log.error("[Learn & Earn][QA] account[{}] start quiz [{}] error, {}",
                        fullAccountContext.getId(), quizId, e.getMessage()
                );
                if (!e.getMessage().contains("Quiz already started")) {
                    continue;
                }
            }

            for (QuestionAnswer questionAnswer : entry.getValue()) {
                try {
                    log.info("[Learn & Earn][QA] account[{}] start complete QA[{}/{}]",
                            fullAccountContext.getId(), questionAnswer.getQuizId(), questionAnswer.getQuestionId()
                    );

                    answerQuestion(fullAccountContext, questionAnswer);

                    int sleep = RandomUtil.randomInt(500);
                    log.info("[Learn & Earn][QA] complete QA[{}/{}] success...continue after {} ms",
                            questionAnswer.getQuizId(), questionAnswer.getQuestionId(), sleep
                    );
                    TimeUnit.MILLISECONDS.sleep(sleep);
                } catch (IllegalStateException e) {
                    log.error("[Learn & Earn][QA] complete QA[{}/{}] error break...., {}",
                            questionAnswer.getQuizId(), questionAnswer.getQuestionId(), e.getMessage()
                    );
                    break;
                } catch (Exception e) {
                    errorCount++;
                    log.error("[Learn & Earn][QA] complete QA[{}/{}] error, {}",
                            questionAnswer.getQuizId(), questionAnswer.getQuestionId(), e.getMessage()
                    );
                }
            }
        }
        log.info("[Learn & Earn][QA] QA task complete [{}/{}]",
                questionAnswers.size() - errorCount, questionAnswers.size());
    }

    private void answerQuestion(
            FullAccountContext fullAccountContext, QuestionAnswer questionAnswer
    ) throws ExecutionException, InterruptedException {
        Map<String, String> headers = buildHeader(fullAccountContext);
        JSONObject body = new JSONObject();
        body.put("answerId", questionAnswer.getAnswerId());
        body.put("quizId", questionAnswer.getQuizId());
        body.put("questionId", questionAnswer.getQuestionId());

        JSONObject result = RestApiClientFactory.getClient(fullAccountContext.getProxy()).request(
                BASE_URL + "/learn-earn/check-question",
                HttpMethod.POST,
                headers,
                null,
                body,
                2
        ).thenApply(JSONObject::parseObject).get();

        questionAnswer.setAnswerId(result.getString("correctAnswer"));
        if (!questionAnswer.getAnswerId().equals(result.getString("correctAnswer"))) {
            throw new IllegalStateException("question answer is not correct, need " + result.getString("correctAnswer"));
        }
    }

    private void startQuiz(FullAccountContext fullAccountContext, String quizId) throws ExecutionException, InterruptedException {
        Map<String, String> headers = buildHeader(fullAccountContext);
        RestApiClientFactory.getClient(fullAccountContext.getProxy()).request(
                BASE_URL + "/learn-earn/start-quiz/" + quizId,
                HttpMethod.POST,
                headers,
                null,
                new JSONObject(),
                1
        ).get();
    }

    private List<QuestionAnswer> queryAccountAvailableQATask(FullAccountContext fullAccountContext) throws ExecutionException, InterruptedException {
        Map<String, String> headers = buildHeader(fullAccountContext);
        return RestApiClientFactory.getClient(fullAccountContext.getProxy()).listRequest(
                BASE_URL + "/learn-earn",
                HttpMethod.GET,
                headers,
                null,
                null
        ).thenApply(result -> {
            List<QuestionAnswer> questionAnswers = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                JSONObject quiz = result.getJSONObject(i);
                String quizId = quiz.getString("id");
                Map<String, String> qaMap = answerMap.get(quizId);

                if (qaMap == null || qaMap.isEmpty()) {
                    qaMap = new HashMap<>();
                }

                JSONArray questions = quiz.getJSONArray("questions");
                for (int i1 = 0; i1 < questions.size(); i1++) {
                    JSONObject question = questions.getJSONObject(i1);
                    String questionId = question.getString("id");
                    String answerId = qaMap.get(questionId);
                    if (StrUtil.isBlank(answerId)) {
                        answerId = question.getJSONArray("answers")
                                .getJSONObject(0).getString("id");
                    }

                    questionAnswers.add(new QuestionAnswer(
                            quizId, questionId, answerId
                    ));
                }
            }
            return questionAnswers;
        }).get();
    }


    private void completeViewTask(FullAccountContext accountContext, String taskId) throws ExecutionException, InterruptedException {
        Map<String, String> headers = buildHeader(accountContext);
        RestApiClientFactory.getClient(accountContext.getProxy()).jsonRequest(
                BASE_URL + "/tasks/start/" + taskId,
                HttpMethod.POST,
                headers,
                null,
                new JSONObject()
        ).get();
    }

    private List<String> queryAccountAvailableViewTask(FullAccountContext accountContext)
            throws ExecutionException, InterruptedException {
        Map<String, String> headers = buildHeader(accountContext);
        return RestApiClientFactory.getClient(accountContext.getProxy()).jsonRequest(
                BASE_URL + "/tasks/my",
                HttpMethod.GET,
                headers,
                null,
                null
        ).thenApply(result -> {
            JSONArray available = result.getJSONArray("available");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < available.size(); i++) {
                JSONObject task = available.getJSONObject(i);
                if (
                        "LEARN_EARN".equals(task.getString("type")) &&
                                "ACTIVE".equals(task.getString("taskStatus"))
                ) {
                    ids.add(task.getString("id"));
                }
            }
            return ids;
        }).get();
    }


    public Map<String, String> buildHeader(FullAccountContext accountContext) {
        Object cookie = accountContext.getParam(HeaderKey.COOKIE);
        if (cookie == null) {
            throw new IllegalArgumentException("cookie is null");
        }

        Map<String, String> headers = accountContext.buildHeader();
        headers.put(HeaderKey.COOKIE, (String) cookie);
        headers.put(HeaderKey.ORIGIN, "https://quests.agnthub.ai");
        headers.put(HeaderKey.REFERER, "https://quests.agnthub.ai/");
        headers.put(HeaderKey.ACCEPT, "application/json, text/plain, */*");
        return headers;
    }
}
