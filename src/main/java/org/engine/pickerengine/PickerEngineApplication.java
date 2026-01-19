package org.engine.pickerengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PickerEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PickerEngineApplication.class, args);
    }

}
