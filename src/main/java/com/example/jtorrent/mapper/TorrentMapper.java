package com.example.jtorrent.mapper;

import com.example.jtorrent.dto.TorrentFileResponse;
import com.example.jtorrent.dto.TorrentResponse;
import com.example.jtorrent.model.Torrent;
import com.example.jtorrent.model.TorrentFile;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class TorrentMapper {

    public TorrentResponse toResponse(Torrent torrent) {
        if (torrent == null) {
            return null;
        }

        return TorrentResponse.builder()
                .id(torrent.getId())
                .infoHash(torrent.getInfoHash())
                .name(torrent.getName())
                .magnetLink(torrent.getMagnetLink())
                .totalSize(torrent.getTotalSize())
                .downloadedSize(torrent.getDownloadedSize())
                .uploadedSize(torrent.getUploadedSize())
                .status(torrent.getStatus())
                .progress(torrent.getProgress())
                .downloadSpeed(torrent.getDownloadSpeed())
                .uploadSpeed(torrent.getUploadSpeed())
                .peers(torrent.getPeers())
                .seeds(torrent.getSeeds())
                .savePath(torrent.getSavePath())
                .addedDate(torrent.getAddedDate())
                .completedDate(torrent.getCompletedDate())
                .files(torrent.getFiles().stream()
                        .map(this::toFileResponse)
                        .collect(Collectors.toList()))
                .errorMessage(torrent.getErrorMessage())
                .comment(torrent.getComment())
                .createdBy(torrent.getCreatedBy())
                .creationDate(torrent.getCreationDate())
                .build();
    }

    public TorrentFileResponse toFileResponse(TorrentFile file) {
        if (file == null) {
            return null;
        }

        return TorrentFileResponse.builder()
                .id(file.getId())
                .path(file.getPath())
                .size(file.getSize())
                .downloadedSize(file.getDownloadedSize())
                .progress(file.getProgress())
                .priority(file.getPriority())
                .build();
    }
}
