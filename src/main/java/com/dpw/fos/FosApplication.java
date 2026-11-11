package com.dpw.fos;

import com.dpw.fos.config.FosProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(FosProperties.class)
public class FosApplication
    extends org.springframework.boot.web.servlet.support.SpringBootServletInitializer {
  @Override
  protected org.springframework.boot.builder.SpringApplicationBuilder configure(
      org.springframework.boot.builder.SpringApplicationBuilder app) {
    return app.sources(FosApplication.class);
  }

  public static void main(String[] args) {
    SpringApplication.run(FosApplication.class, args);
  }
}
