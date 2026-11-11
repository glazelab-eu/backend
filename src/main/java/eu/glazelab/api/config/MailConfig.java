package eu.glazelab.api.config;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class MailConfig {
    /**
     * Account mail leaves the request thread here. Bounded: a full queue drops the mail with a warning (the answer stays
     * the same; the user can ask again) instead of failing the request. Queued mail is still sent on shutdown.
     */
    @Bean public ThreadPoolTaskExecutor mailExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("glazelab-mail-");
        executor.setRejectedExecutionHandler((task, pool) ->
                LoggerFactory.getLogger(MailConfig.class).warn("Account email dropped: the mail queue is full or shut down"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
