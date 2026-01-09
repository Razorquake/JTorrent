package com.example.jtorrent.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateFilePrioritiesRequest {
    private List<Long> fileIds;
    private Integer priority;
}
