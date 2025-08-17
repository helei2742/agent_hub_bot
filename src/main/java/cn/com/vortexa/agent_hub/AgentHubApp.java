package cn.com.vortexa.agent_hub;

import cn.com.vortexa.bot_template.BotTemplateAutoConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@ImportAutoConfiguration(BotTemplateAutoConfig.class)
public class AgentHubApp {
    public static void main(String[] args) {
        SpringApplication.run(AgentHubApp.class, args);
    }
}
