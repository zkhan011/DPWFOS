package com.dpw.fos.modules;

import com.dpw.fos.simulator.SimulatorProperties;
import com.dpw.fos.uitv.UitvProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties({UitvProperties.class, SimulatorProperties.class})
public class ModuleConfiguration {
  @Bean
  Clock telemetryClock() {
    return Clock.systemDefaultZone();
  }
}
