package com.neurovault.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileItemDto {

    private UUID id;

    @JsonProperty("filename")
    @JsonAlias({"filename", "name"})
    private String filename;

    @JsonProperty("sizeBytes")
    @JsonAlias({"sizeBytes", "size"})
    private Long sizeBytes;

    private Integer chunkCount;
    private LocalDateTime createdAt;
}
