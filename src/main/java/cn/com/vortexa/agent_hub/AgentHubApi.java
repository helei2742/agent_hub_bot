package cn.com.vortexa.agent_hub;


import cn.com.vortexa.agent_hub.dto.QuestionAnswer;
import cn.com.vortexa.base.constants.HeaderKey;
import cn.com.vortexa.bot_template.bot.dto.FullAccountContext;
import cn.com.vortexa.common.constants.HttpMethod;
import cn.com.vortexa.common.util.CastUtil;
import cn.com.vortexa.common.util.http.RestApiClientFactory;
import cn.com.vortexa.mail.factory.MailReaderFactory;
import cn.com.vortexa.mail.reader.MailReader;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ResponseBody;

import javax.mail.Message;
import javax.mail.MessagingException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author helei
 * @since 2025-08-17
 */
@Slf4j
public class AgentHubApi {
    public static final String BASE_URL = "https://hub-api.agnthub.ai/api";
    public static final String MAIL_FROM = "no-reply@mail.privy.io";
    public static final Pattern V_CODE_PATTERN = Pattern.compile("\\b\\d{6}\\b");
    public static final String IMAP_PASSWORD = "imap_password";

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


    public String signInAccount(FullAccountContext fullAccountContext) throws Exception {
        log.info("account[{}] send get check code request...", fullAccountContext.getAccount());
        sendSignInInit(fullAccountContext).get();

        log.info("account[{}] get check code from email...", fullAccountContext.getAccount());
        String checkCode = getAccountCheckCode(fullAccountContext);
        if (StrUtil.isBlank(checkCode)) {
            throw new RuntimeException("get email check code failed");
        }
        log.info("account[{}] get check code success, code: {}...", fullAccountContext.getAccount(), checkCode);
        log.info("account[{}] send sign in request...", fullAccountContext.getAccount());

        Map<String, String> headers = buildHeader(fullAccountContext);
        headers.put("privy-app-id", "cm6jesuxd00a9ojo0i9rlxudk");
        headers.put("privy-ca-id", "695ef102-583a-4816-bb32-d8e225ba08fe");
        headers.put("privy-client", "react-auth:2.4.2");

        JSONObject body = new JSONObject();
        body.put("code", checkCode);
        body.put("email", fullAccountContext.getAccount());
        body.put("mode", "login-or-sign-up");
        RestApiClientFactory.getClient(fullAccountContext.getProxy()).rawRequest(
                "https://privy.agnthub.ai/api/v1/passwordless/authenticate",
                HttpMethod.POST,
                headers,
                null,
                body,
                response -> {
                    if (response.code() != 200) {
                        ResponseBody rb = response.body();
                        throw new RuntimeException("get email check code failed, " + (rb == null ? "" : rb.string()));
                    }
                    List<String> cookies = response.headers("Set-Cookie");
                    String cookie = cookies.stream()
                            .map(c -> c.split(";", 2)[0]) // 只取 "key=value"，去掉 path, httponly 等属性
                            .collect(Collectors.joining("; "));

                    fullAccountContext.putParam(HeaderKey.COOKIE, cookie);
                }
        ).get();

        if (fullAccountContext.getParam(HeaderKey.COOKIE) == null) {
            throw new RuntimeException("get cookie failed");
        }

        return CastUtil.autoCast(fullAccountContext.getParam(HeaderKey.COOKIE));
    }

    private String getAccountCheckCode(FullAccountContext fullAccountContext) throws InterruptedException {
        String password = CastUtil.autoCast(fullAccountContext.getParam(IMAP_PASSWORD));
        if (StrUtil.isBlank(password)) {
            throw new IllegalArgumentException("imap password is empty");
        }

        MailReader mailReader = MailReaderFactory.getImapMailReader(fullAccountContext.getAccount());

        AtomicReference<String> checkCode = new AtomicReference<>();


        for (int i = 0; i < 3; i++) {
            TimeUnit.SECONDS.sleep(5);
            mailReader.stoppableReadMessage(
                    fullAccountContext.getAccount(),
                    password,
                    3,
                    message -> {
                        try {
                            if (isReceivedWithinOneMinute(message)) {
                                String code = resolveVerifierCodeFromMessage(message);
                                checkCode.set(code);
                                return StrUtil.isNotBlank(code);
                            } else {
                                return false;
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
            );
            if (checkCode.get() != null) {
                break;
            }
        }

        return checkCode.get();
    }

    private String resolveVerifierCodeFromMessage(Message message) throws MessagingException {
        boolean b = Arrays.stream(message.getFrom())
                .anyMatch(address -> address.toString().contains(MAIL_FROM));
        if (!b) return null;

        String context = MailReader.getTextFromMessage(message);
        Matcher matcher = V_CODE_PATTERN.matcher(context);

        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static boolean isReceivedWithinOneMinute(Message message) throws Exception {
        Date receivedDate = message.getReceivedDate();
        if (receivedDate == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long diff = now - receivedDate.getTime();
        return diff >= 0 && diff <= 60 * 1000; // 最近一分钟内
    }

    private CompletableFuture<JSONObject> sendSignInInit(FullAccountContext fullAccountContext) {
        Map<String, String> headers = buildHeader(fullAccountContext);
        headers.put("privy-app-id", "cm6jesuxd00a9ojo0i9rlxudk");
        headers.put("privy-ca-id", "695ef102-583a-4816-bb32-d8e225ba08fe");
        headers.put("privy-client", "react-auth:2.4.2");
        JSONObject body = new JSONObject();
        body.put("email", fullAccountContext.getAccount());
        return RestApiClientFactory.getClient(fullAccountContext.getProxy()).jsonRequest(
                "https://privy.agnthub.ai/api/v1/passwordless/init",
                HttpMethod.POST,
                headers,
                null,
                body
        );
    }


    public void answerQuestion(
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

    public void startQuiz(FullAccountContext fullAccountContext, String quizId) throws ExecutionException, InterruptedException {
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

    public List<QuestionAnswer> queryAccountAvailableQATask(FullAccountContext fullAccountContext) throws ExecutionException, InterruptedException {
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


    public void completeViewTask(FullAccountContext accountContext, String taskId) throws ExecutionException, InterruptedException {
        Map<String, String> headers = buildHeader(accountContext);
        RestApiClientFactory.getClient(accountContext.getProxy()).jsonRequest(
                BASE_URL + "/tasks/start/" + taskId,
                HttpMethod.POST,
                headers,
                null,
                new JSONObject()
        ).get();
    }

    public List<String> queryAccountAvailableViewTask(FullAccountContext accountContext)
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


    public JSONObject dailyCheckIn(FullAccountContext fullAccountContext) throws ExecutionException, InterruptedException {
        Map<String, String> headers = buildHeader(fullAccountContext);
        return RestApiClientFactory.getClient(fullAccountContext.getProxy()).jsonRequest(
                BASE_URL + "/daily-rewards/claim",
                HttpMethod.POST,
                headers,
                null,
                new JSONObject()
        ).get();
    }


    private Map<String, String> buildHeader(FullAccountContext accountContext) {
        Map<String, String> headers = accountContext.buildHeader();

        Object cookie = accountContext.getParam(HeaderKey.COOKIE);
        if (cookie != null) {
            headers.put(HeaderKey.COOKIE, (String) cookie);
        }

        headers.put(HeaderKey.ORIGIN, "https://quests.agnthub.ai");
        headers.put(HeaderKey.REFERER, "https://quests.agnthub.ai/");
        headers.put(HeaderKey.ACCEPT, "application/json, text/plain, */*");
        headers.put(HeaderKey.CONTENT_TYPE, "application/json");
        return headers;
    }
}
