package com.example.jtorrent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class JTorrentApplication {

    public static void main(String[] args) {
        SpringApplication.run(JTorrentApplication.class, args);
    }

}
