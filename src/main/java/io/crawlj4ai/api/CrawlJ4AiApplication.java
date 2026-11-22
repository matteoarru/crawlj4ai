package io.crawlj4ai.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CrawlJ4AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrawlJ4AiApplication.class, args);
    }
}
