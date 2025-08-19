package cn.com.vortexa.agent_hub;

import cn.com.vortexa.bot_template.bot.AbstractVortexaBot;
import cn.com.vortexa.bot_template.bot.VortexaBotContext;
import cn.com.vortexa.bot_template.bot.anno.VortexaBot;
import cn.com.vortexa.bot_template.bot.anno.VortexaBotAPI;
import cn.com.vortexa.bot_template.bot.anno.VortexaBotCatalogueGroup;
import cn.com.vortexa.bot_template.bot.dto.FullAccountContext;
import cn.com.vortexa.bot_template.constants.BotAppConnectStatus;
import cn.com.vortexa.agent_hub.dto.QuestionAnswer;
import cn.com.vortexa.bot_template.constants.VortexaBotApiSchedulerType;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;

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
        namespace = "agent_hub",
        websiteUrl = "https://quests.agnthub.ai",
        catalogueGroup = {
                @VortexaBotCatalogueGroup(name = "每日", order = 1),
                @VortexaBotCatalogueGroup(name = "一次性任务", order = 2),
        }
)
public class AgentHubBot extends AbstractVortexaBot {

    private final AgentHubApi agentHubApi = new AgentHubApi();

    public AgentHubBot(VortexaBotContext vortexaBotContext) {
        super(vortexaBotContext);
    }

    @VortexaBotAPI(
            name = "Check In",
            catalogueName = "每日",
            catalogueOrder = 1,
            connectStatus = BotAppConnectStatus.OFFLINE,
            schedulerType = VortexaBotApiSchedulerType.INTERVAL
    )
    public void dailyCheckIn() {
        forEachAccountContext((pageResult, i, fullAccountContext) -> {
            log.info("[Check In] start [{}] daily check in...", fullAccountContext.getAccount());

            if (!tryLogin(fullAccountContext)) return;
            try {
                JSONObject jsonObject = agentHubApi.dailyCheckIn(fullAccountContext);
                log.info("[Check In] [{}] daily check in complete, {}",
                        fullAccountContext.getAccount(), jsonObject);
            } catch (Exception e) {
                log.error("[Check In] daily check in error, {}" , e.getMessage());
            }
        });
    }

    @VortexaBotAPI(
            name = "Learn & Earn-[view-task]",
            catalogueName = "一次性任务",
            catalogueOrder = 1,
            connectStatus = BotAppConnectStatus.OFFLINE
    )
    public void completeLearnAndEarnViewTask() {
       forEachAccountContext((pageResult, i, fullAccountContext) -> {
           if (!tryLogin(fullAccountContext)) return;

           List<String> ids = null;
           try {
               ids = agentHubApi.queryAccountAvailableViewTask(fullAccountContext);
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
                   agentHubApi.completeViewTask(fullAccountContext, id);
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

       });
    }

    @VortexaBotAPI(
            name = "Learn & Earn-[QA-task]",
            catalogueName = "一次性任务",
            catalogueOrder = 2,
            connectStatus = BotAppConnectStatus.OFFLINE
    )
    public void completeLearnAndEarnQATask() {
       forEachAccountContext((pageResult, i, fullAccountContext) -> {
           if (!tryLogin(fullAccountContext)) return;
           List<QuestionAnswer> questionAnswers = null;
           try {
               questionAnswers = agentHubApi.queryAccountAvailableQATask(fullAccountContext);
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
                   agentHubApi.startQuiz(fullAccountContext, quizId);
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

                       agentHubApi.answerQuestion(fullAccountContext, questionAnswer);

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

       });
    }


    private boolean tryLogin(FullAccountContext fullAccountContext) {
        try {
            log.info("start sign in account[{}], {}", fullAccountContext.getId(),
                    fullAccountContext.getProxy() == null ? "No Proxy" : fullAccountContext.getProxy()
            );
            String cookie = agentHubApi.signInAccount(fullAccountContext);
            if (StrUtil.isBlank(cookie)) {
                log.info("sign in account[{}] fail", fullAccountContext.getId());
                return false;
            }
        } catch (Exception e) {
            log.error("sign in account[{}] error", fullAccountContext.getId(), e);
            return false;
        }
        log.info("sign in account[{}] success", fullAccountContext.getId());
        return true;
    }
}
