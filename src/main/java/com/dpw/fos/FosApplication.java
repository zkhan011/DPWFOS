package com.dpw.fos;

import com.dpw.fos.config.FosProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(FosProperties.class)
public class FosApplication {
  public static void main(String[] args) {
    SpringApplication.run(FosApplication.class, args);
  }
}
