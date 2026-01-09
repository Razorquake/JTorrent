package com.example.jtorrent.dto;

import com.example.jtorrent.model.TorrentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TorrentFilterRequest {

    private TorrentStatus status;
    private List<TorrentStatus> statuses;
    private String name;
    private Double minProgress;
    private Double maxProgress;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Long minSize;
    private Long maxSize;
    private Boolean hasErrors;

    // Sorting
    private String sortBy = "addedDate"; // Default sort field
    private String sortDirection = "DESC"; // ASC or DESC

    // Pagination
    private Integer page = 0;
    private Integer size = 20;
}
