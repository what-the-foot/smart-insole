package com.smartinsole;

import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.global.config.AuthProperties;
import com.smartinsole.global.config.CorsProperties;
import com.smartinsole.global.config.IngestionProperties;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.global.config.ReceiverProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties({
        AuthProperties.class,
        ReceiverProperties.class,
        IngestionProperties.class,
        RealtimeProperties.class,
        AnalysisProperties.class,
        CorsProperties.class
})
public class SmartInsoleApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartInsoleApplication.class, args);
    }
}
