package com.example.jtorrent.dto;

import lombok.Data;

import java.util.List;

@Data
public class SkipFilesRequest {
    List<Long> fileIds;
}
