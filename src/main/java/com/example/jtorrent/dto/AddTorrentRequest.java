package com.example.jtorrent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddTorrentRequest {

    @NotBlank(message = "Magnet link or torrent file is required")
    private String magnetLink;

    private String savePath; // Optional custom save path

    private Boolean startImmediately = true; // Auto-start download

}
