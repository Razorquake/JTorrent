package com.example.jtorrent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "torrent")
@Data
public class TorrentProperties {

    private String downloadsPath = "./downloads";
    private Integer maxConcurrentDownloads = 5;
    private Integer peerConnectionPortMin = 6881;
    private Integer peerConnectionPortMax = 6889;

}
